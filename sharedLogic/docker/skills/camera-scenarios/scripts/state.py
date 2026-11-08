"""Owner-scoped scenario state. Tokens remain private, outside the immutable Skill bundle."""
import json
import fcntl
import os
from pathlib import Path
import re
import secrets
import stat
import sys


def require(condition, code="invalid_scenario"):
    if not condition:
        raise ValueError(code)


def scenario_dir(identity):
    require(bool(re.fullmatch(r"cam-[a-f0-9-]{36}", identity)), "invalid_scenario_id")
    root = os.environ.get("SOUZ_SKILL_STATE_DIR")
    require(bool(root), "skill_state_unavailable")
    path = Path(root)
    require(not path.is_symlink(), "unsafe_state")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = path / identity
    require(not path.is_symlink(), "unsafe_state")
    return path


def read_private(path):
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, "r", encoding="utf-8") as f:
        info = os.fstat(f.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_mode & 0o077 == 0, "unsafe_state_permissions")
        require(info.st_size <= 65536, "state_too_large")
        return f.read(65537)


def atomic_write(path, value):
    require(len(value.encode("utf-8")) <= 65536, "state_too_large")
    temporary = path.with_name(path.name + "." + secrets.token_hex(8))
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(value)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def mcp(raw):
    value = json.loads(raw)
    for _ in range(6):
        require(isinstance(value, dict), "invalid_detector_response")
        require(value.get("status", "succeeded") == "succeeded", "detector_call_unconfirmed")
        require(value.get("isError") is not True, "detector_rejected")
        if value.get("ok") is True:
            return value
        if "result" in value:
            value = value["result"]
        elif "structuredContent" in value:
            value = value["structuredContent"]
        else:
            texts = [x.get("text") for x in value.get("content", []) if x.get("type") == "text"]
            require(len(texts) == 1, "invalid_detector_response")
            value = json.loads(texts[0])
    raise ValueError("invalid_detector_response")


def hook(raw, absent=False):
    value = json.loads(raw)
    require(isinstance(value, dict), "invalid_hook_response")
    require(value.get("status") == "succeeded" or
            (absent and value.get("error", {}).get("code") == "hook_not_found"), "hook_operation_unconfirmed")
    return value


def preflight(raw, camera, condition):
    capabilities = mcp(raw)["capabilities"]
    condition = json.loads(condition)
    require(capabilities.get("runtime_state") == "ready", "detector_not_ready")
    require(camera in capabilities.get("camera_ids", []), "camera_unavailable")
    require(isinstance(condition, dict), "invalid_condition")
    classes = condition.get("classes")
    require(isinstance(classes, list) and classes and all(isinstance(x, str) for x in classes), "invalid_classes")
    require(set(classes).issubset(capabilities.get("active_classes", [])), "unsupported_active_class")
    require(len(set(classes)) == len(classes), "duplicate_classes")
    require(set(condition).issubset({"classes", "min_confidence", "roi", "motion", "dwell_ms"}), "unsupported_condition")
    confidence = condition.get("min_confidence", 0.5)
    require(type(confidence) in (int, float) and 0 <= confidence <= 1, "invalid_confidence")
    dwell = condition.get("dwell_ms", 1000)
    require(type(dwell) is int and 0 <= dwell <= 600000, "invalid_dwell")
    roi = condition.get("roi", {"x": 0, "y": 0, "width": 1, "height": 1})
    require(isinstance(roi, dict) and set(roi) == {"x", "y", "width", "height"}, "invalid_roi")
    require(all(type(x) in (int, float) for x in roi.values()), "invalid_roi")
    require(0 <= roi["x"] < 1 and 0 <= roi["y"] < 1 and roi["width"] > 0 and roi["height"] > 0
            and roi["x"] + roi["width"] <= 1 and roi["y"] + roi["height"] <= 1, "invalid_roi")
    if "motion" in condition:
        motion = condition["motion"]
        require(isinstance(motion, dict) and set(motion) == {"min_changed_fraction"}, "invalid_motion")
        fraction = motion["min_changed_fraction"]
        require(type(fraction) in (int, float) and 0 < fraction <= 1, "invalid_motion")


def run(args):
    command = args[0]
    if command == "validate_delete":
        scenario_dir(args[1])
        require(bool(re.fullmatch(r"[a-f0-9]{64}", args[2])), "invalid_hook_revision")
        revision = json.loads(args[3])
        require(type(revision) is int and 1 <= revision <= 9007199254740991, "invalid_detector_revision")
    elif command == "preflight":
        preflight(*args[1:])
    elif command == "check_hook":
        hook(args[1])
    elif command == "check_mcp":
        mcp(args[1])
    elif command == "prepare":
        identity, channel, target, camera, condition, cooldown, prompt = args[1:]
        path = scenario_dir(identity)
        value = {"channelId": channel, "target": target, "cameraId": camera,
                 "condition": json.loads(condition), "cooldownMs": json.loads(cooldown), "prompt": prompt}
        require(type(value["cooldownMs"]) is int and 0 <= value["cooldownMs"] <= 86400000, "invalid_cooldown")
        path.mkdir(mode=0o700, exist_ok=True)
        record = path / "scenario.json"
        token_path = path / "token"
        if record.exists():
            require(json.loads(read_private(record))["config"] == value, "scenario_configuration_conflict")
        else:
            # Create a durable recovery record before any hook/device side effects.
            atomic_write(record, json.dumps({"scenarioId": identity, "config": value, "state": "prepared"}, ensure_ascii=False))
        if not token_path.exists():
            atomic_write(token_path, secrets.token_urlsafe(48))
        token = read_private(token_path)
        require(32 <= len(token) <= 512 and not any(c.isspace() for c in token), "invalid_private_credential")
        sys.stdout.write(token)
    elif command == "token":
        sys.stdout.write(read_private(scenario_dir(args[1]) / "token"))
    elif command == "record":
        identity, phase, raw = args[1:]
        data = mcp(raw)
        path = scenario_dir(identity) / "scenario.json"
        value = json.loads(read_private(path))
        value["state"] = phase
        if "rule" in data:
            value["detectorRevision"] = data["rule"]["revision"]
        atomic_write(path, json.dumps(value, ensure_ascii=False))
    elif command == "forget":
        path = scenario_dir(args[1])
        for name in ["scenario.json", "token"]:
            (path / name).unlink(missing_ok=True)
        if path.exists():
            path.rmdir()
    elif command == "list":
        root = os.environ.get("SOUZ_SKILL_STATE_DIR")
        require(bool(root), "skill_state_unavailable")
        values = []
        for path in sorted(Path(root).glob("cam-*/scenario.json")):
            values.append(json.loads(read_private(path)))
        print(json.dumps(values))
    else:
        raise ValueError("unknown_command")


if __name__ == "__main__":
    os.umask(0o077)
    try:
        arguments = sys.argv[1:]
        if arguments and arguments[0] in {"prepare", "token", "record", "forget"}:
            directory = scenario_dir(arguments[1])
            lock_path = directory.parent / (directory.name + ".lock")
            fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
            with os.fdopen(fd, "a+") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                run(arguments)
        else:
            run(arguments)
    except Exception as error:
        # Never echo arguments, remote responses or private files in errors.
        print(str(error) if isinstance(error, ValueError) and re.fullmatch(r"[a-z_]+", str(error))
              else "camera_scenario_step_failed", file=sys.stderr)
        sys.exit(1)

# Мост «скилл → тул»: детерминированный вызов уже разрешённых тулов из скрипта скилла

## Зачем

Файловые скиллы (`RunSkillCommand` → `SkillCommandExecutor`) исполняются как изолированный
процесс в песочнице (`SandboxCommandRuntime.BASH/PYTHON/NODE/PROCESS`). У этого процесса нет пути
обратно в живой тулинг разговора: например, `device.mcp.call_tool` — скомпилированный
`LLMToolSetup`, привязанный к текущему `ToolInvocationMeta`/websocket-соединению, вызвать который
может только сама LLM внутри своего хода. Из-за этого любой многошаговый детерминированный цикл
(прочитать состояние → решить → выполнить действие → проверить) раньше приходилось либо вести
самой модели ходами разговора, либо выносить во внешний, не связанный с Союзом сервис.

Мост убирает это ограничение: скрипт скилла может синхронно вызвать конкретный, заранее
разрешённый набор тулов через локальный Unix-сокет, не порождая видимых ходов разговора и не
покидая песочницу.

## Как получить доступ

В манифесте `SKILL.md` скилла нужно явно перечислить, какие тулы ему разрешено вызывать через
мост:

```yaml
---
name: my-skill
description: ...
metadata:
  souz.bridge-tools: device.mcp.call_tool, device.mcp.list_tools
---
```

Без этого поля (или с пустым списком) мост для скрипта не поднимается вообще — `fail closed`, а
не «доступно всё, что доступно модели». Список — часть контента `SKILL.md`, поэтому любое его
расширение меняет `bundleHash` и требует повторного прохождения `SkillApprovalGate`.

Во время выполнения реально доступное множество — это пересечение задекларированного списка с
тем, что и так включено для текущего разговора (`AgentToolsFilter`). Раздел `souz.bridge-tools`
не может *расширить* права скилла сверх того, что уже разрешено — только сузить.

## Протокол

Пока скрипт запущен, ему в окружение передаётся `SOUZ_TOOL_BRIDGE_SOCK` — путь к Unix-сокету
внутри корня скилла (если allowlist пуст, переменная отсутствует). Один коннект = одно сообщение:

1. Подключиться к сокету (`AF_UNIX`, `SOCK_STREAM`).
2. Отправить JSON-конверт с полем `type`.
3. Полузакрыть сторону записи (`shutdown(SHUT_WR)`).
4. Прочитать ответ до EOF.

Никакого фрейминга сообщений не требуется — соединение одноразовое.

### `type: "tool.call"` — вызов разрешённого тула

```json
{"type": "tool.call", "name": "<tool>", "arguments": {...}}
```

Ответ — либо `content` вызванного тула как есть (то же самое, что увидела бы модель), либо
структурированная ошибка моста: `{"error": {"code": "...", "message": "..."}}` с кодами
`tool_not_allowed` (имя не в allowlist), `tool_not_found` (не в текущем разрешённом наборе тулов),
`invalid_request`, `tool_invocation_failed`.

### `type: "log"` — запись в лог Союза

```json
{"type": "log", "level": "INFO", "message": "..."}
```

Пишется напрямую в лог бэкенда (SLF4J, логгер `ru.souz.tool.skills.bridge.skill.<skillId>`,
с префиксом `user=<userId>`) — не через `toolCatalog`, allowlist на неё не действует: раз мост
вообще поднят (allowlist для `tool.call` непуст), логирование доступно всегда. `level` — одно из
`TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR` (регистронезависимо, по умолчанию `INFO`). Ответ — `{"ok":
true}` при успехе или `{"error": {...}}` при пустом/некорректном сообщении.

Смысл — чтобы пошаговая диагностика скрипта (какой тул вызван когда, сколько заняло, какие
LLM-запросы улетели) оказалась в том же потоке, что и остальной лог бэкенда, а не терялась в
`stderr` процесса, видимом только внутри `stderr`-поля ответа `RunSkillCommand`.

## Референсный хелпер

`python3` есть в любом рантайме песочницы (проверяется при старте sandbox-контейнера), поэтому
хелпер написан на нём и не тянет внешних зависимостей. Скопируйте это как
`scripts/tool-call.py` внутрь своего скилла:

```python
#!/usr/bin/env python3
"""Bridge client: tool.call and log envelopes. Usage: tool-call.py <name> '<json-args>'"""
import json
import os
import socket
import sys


def _send(envelope: dict) -> dict:
    socket_path = os.environ.get("SOUZ_TOOL_BRIDGE_SOCK")
    if not socket_path:
        raise RuntimeError("SOUZ_TOOL_BRIDGE_SOCK is not set — this skill has no bridge access declared")
    request = json.dumps(envelope).encode("utf-8")
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.connect(socket_path)
        sock.sendall(request)
        sock.shutdown(socket.SHUT_WR)
        chunks = []
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            chunks.append(chunk)
    return json.loads(b"".join(chunks).decode("utf-8"))


def call_tool(name: str, arguments: dict) -> dict:
    return _send({"type": "tool.call", "name": name, "arguments": arguments})


def log(level: str, message: str) -> None:
    try:
        _send({"type": "log", "level": level, "message": message})
    except Exception:
        pass  # logging must never break the actual task


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: tool-call.py <tool-name> '<json-arguments>'", file=sys.stderr)
        return 2
    try:
        arguments = json.loads(sys.argv[2])
    except json.JSONDecodeError as e:
        print(f"invalid JSON arguments: {e}", file=sys.stderr)
        return 2
    try:
        response = call_tool(sys.argv[1], arguments)
    except RuntimeError as e:
        print(str(e), file=sys.stderr)
        return 2
    print(json.dumps(response))
    return 1 if isinstance(response, dict) and "error" in response else 0


if __name__ == "__main__":
    raise SystemExit(main())
```

Вызов из BASH-скрипта скилла:

```bash
result=$(python3 scripts/tool-call.py device.mcp.call_tool '{"name":"get_screen","arguments":{}}')
```

Логирование пошагового прогресса — из питона напрямую через `log(level, message)`, либо
прицепить `logging.Handler`, вызывающий её, к своему логгеру (см. `BridgeLogHandler` в
`tv-control`-скилле — весь существующий `logging.info(...)`/`.warning(...)` начинает лететь в
лог Союза без правки самих вызовов).

`tool-call.py` возвращает ненулевой exit code только при ошибке *самого моста* (`tool_not_allowed`,
`tool_not_found` и т.п.) — код 0 при успешном вызове тула, даже если у вызванного тула
`isError: true` в собственном теле ответа (это относится к результату операции, а не к факту
вызова, и разбирается скриптом отдельно — см. `isError`/`ClientError` в контракте
`device.mcp.call_tool`).

## Известное ограничение: Docker-песочница на macOS

Мост уже реализован для обоих режимов `RuntimeSandbox` (`LOCAL`/`DOCKER`), но для `DOCKER`
экспериментально подтверждено: если демон Docker работает внутри виртуальной машины относительно
процесса Союза (Docker Desktop и colima на macOS — оба именно так и работают), то bind-mount,
через который сегодня проброшены остальные файлы песочницы, **не проксирует живой Unix-сокет** —
внутри контейнера файл сокета виден (`srwxr-xr-x`), но подключение к нему падает с
`ECONNREFUSED`. Виртуализованная файловая прослойка (gRPC-FUSE/VirtioFS/9p) передаёт метаданные
файла, но не сам сокет-канал. Проверено вручную вне тестов Kotlin — это ограничение окружения, а
не баг конкретной реализации.

На «настоящем» Linux-хосте (демон Docker работает нативно на том же ядре, без VM) это ограничение
не должно проявляться, но это пока не подтверждено. Пока не проверено на таком хосте — считайте
поддержку моста в `DOCKER`-режиме непроверенной; `LOCAL`-режим (нужный для сценария управления ТВ
через desktopApp) этой границы виртуализации не пересекает и работает штатно.

## Что не входит в этот механизм

- Ограничение по *аргументам* (например, фиксация `target` у `device.mcp.call_tool` конкретным
  устройством) — сейчас allowlist работает только на уровне имени тула. Возможное будущее
  усиление, не блокирующее текущую версию.
- Параллельные/асинхронные вызовы из одного скрипта — мост это не запрещает (каждое соединение
  независимо), но `tool-call.py` в текущем виде — синхронный, одно соединение за раз.

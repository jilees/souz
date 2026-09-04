# Запуск Docker-песочницы для бэкенда — заметки по Phase 1

Статус: **экспериментально, работает на тестовом VPS (`212.19.134.54`) с 2026-09-03.** Не
готово к проду (Phase 1.5, §6, всё ещё открыта). Документ фиксирует все временные и
инфраструктурные изменения, которые понадобились, чтобы бэкендный агент заработал в режиме
`SOUZ_SANDBOX_MODE=docker`, зачем каждое было нужно и каким должен быть правильный фикс.
Браузерная часть (Phase 3) прошла через Chrome/Bright Data и вернулась к Lightpanda как
осознанно решённой архитектуре — см. §10 за итог и причины.

Контекст: конечная цель — дать бэкендному агенту настоящий браузер (Docker-песочница +
Lightpanda). Phase 1 — это только «гонять существующие тулы агента внутри Docker-песочницы
вместо прямого исполнения на хосте», браузера пока нет. Переключение режима вскрыло цепочку
пробелов, потому что путь Docker-песочницы на бэкенде почти не был обкатан.

---

## 1. Постоянные изменения (в репозитории, сейчас не закоммичены на `main`)

Это то, что должно нормально влиться, а не воркэраунды:

| Файл | Изменение | Зачем |
|---|---|---|
| `deploy/backend.env.example` | добавлен `SOUZ_SANDBOX_MODE=docker` с комментарием | сделать режим явной задокументированной настройкой деплоя |
| `.github/workflows/deploy-backend.yml` | новый шаг **«Build runtime sandbox image»**: rsync `sharedLogic/Dockerfile` + `sharedLogic/docker/` в `/opt/souz-backend/runtime-sandbox/` на боксе, затем `docker build -t souz-runtime-sandbox:latest` там же | дистрибутив `installDist` не несёт контекст сборки образа; registry не нужен, сборка идёт на том же хосте |
| `sharedLogic/Dockerfile` | в apt-строку добавлены `curl git python3-requests` | скиллы предполагают наличие этих инструментов в своём рантайме (см. §3.2) |
| `sharedLogic/Dockerfile` | добавлен бинарь **Lightpanda** (`/usr/local/bin/lightpanda`) — Phase 2 (см. §8) | headless-браузер для будущего браузерного тула |

Существующий шаг «Upload build and restart» в CD уже делает `systemctl restart souz-backend`
после сборки образа — это обязательно, чтобы новый образ вступил в силу (см. §4).

---

## 2. Разовые ручные шаги на VPS

Поскольку изменения выше не выкатывались через CD, эквивалент был выполнен вручную под
`root` на `212.19.134.54`:

1. Залиты `sharedLogic/Dockerfile` + `sharedLogic/docker/` в `/opt/souz-backend/runtime-sandbox/`
   и там собран `souz-runtime-sandbox:latest` (`docker build`).
2. В `/opt/souz-backend/config/backend.env` дописан `SOUZ_SANDBOX_MODE=docker`
   (бэкап рядом: `backend.env.bak.20260903-110345`).
3. `systemctl restart souz-backend`, проверен `/health`.

Проверено на реальном ходе пользователя: бэкенд лениво создал
`souz-runtime-<userUuid>-<hash>` (`--user 0:0`, bind-mount → `/souz`, keepalive
`sleep 3600`), entrypoint засеял встроенный демо-скилл, `RunSkillCommand` отработал через
`docker exec`.

---

## 3. Временные воркэраунды на VPS

Каждый из них — ручная заплата, **невидимая для CD и для свежего бокса**, и большинство
слетают при рестарте бэкенда или `docker rm`.

### 3.1 Бандлы скиллов не видны в Docker-режиме

**Симптом:** все установленные скиллы (`tv-control`, `calendar`, `yandex-smart-home`,
`device-help`, `aij-help`, `ring-health`, `salute-*`) были «недоступны»; в списке — только
демо-скилл из образа `paper-summarize-academic`.

**Причина:** `FileSystemSkillRegistryRepository` берёт корень скиллов из `skillsDir`
песочницы. LOCAL читает `/root/.local/state/souz/skills/` (плоский, общий для всех юзеров на
этом боксе). DOCKER читает в контейнере `/souz/state/skills` = хост
`<sandbox hostRoot>/state/skills/` — свежее **per-user** дерево, засеянное только демо-скиллом
из образа. У `skill-validations` то же разделение по per-user пути. Реестр делает файловый
IO host-side; через `docker exec` идёт только исполнение команд.

**Симлинки тут не работают:** (1) `DockerSandboxLayout.ensureHostDirectories()` содержит
`check(!Files.isSymbolicLink(path))` на самом `state/skills`; (2) `DockerSandboxFileSystem`
прогоняет `toRealPath()` + `startsWith(hostRoot)`, поэтому пер-скилловый симлинк, цель
которого вне корня песочницы, кидает `ForbiddenFolder`; (3) скрипты скиллов исполняются
внутри контейнера, где симлинк на хостовый путь — битый.

**Воркэраунд:** дерево жёстких ссылок для двух активных юзеров
(`47419d64-0de7-4935-99af-12bfcaaea611`, `cce988eb-92c4-4006-8247-aa05ea89e041`):

```
cp -aln /root/.local/state/souz/{skills,skill-validations}/.  <sandbox hostRoot>/state/{skills,skill-validations}/
```

Хардлинки проходят проверку безопасности (realpath хардлинка — он сам, под `hostRoot`), видны
и исполнимы внутри bind-mount, делят inode (диск почти не тратится; правки «на месте»
подхватываются). Оговорка: если скилл позже *заменят* через атомарную запись реестра (temp +
rename), песочная копия отвяжется и застынет. Новые юзеры всё равно стартуют только с
демо-скиллом.

**Правильный фикс (Phase 1.5):** миграция хранилища скиллов в per-user Docker-деревья при
переключении режима, либо bind-mount общего каталога скиллов в контейнер.

### 3.2 В образе песочницы нет runtime-зависимостей скиллов

**Симптом:** `tv-control` падал, агент говорил «системная ошибка навыка».

**Причина:** `tv-control/scripts` делает `import requests`; в базовом образе
(`node:22-bookworm` + только `bash coreutils findutils python3 ca-certificates`) нет pip и
сторонних Python-пакетов. LOCAL гонял скиллы на хостовом Python (где `requests` был); DOCKER
гоняет их в контейнере. Аудит всех установленных скиллов: `requests` — **единственный**
сторонний Python-импорт вообще; остальные Python-скиллы на stdlib; bash-скиллы используют
`curl`.

**Фикс:** в `sharedLogic/Dockerfile` в apt-строку добавлены `curl git python3-requests`
(Debian-пакеты, без возни с PEP-668/pip), образ пересобран на VPS.

**Правильный фикс (Phase 1.5):** нормальная стратегия runtime-зависимостей скиллов — бейкать
общий набор, `requirements.txt` на скилл, который ставит раннер, либо вендоринг.

### 3.3 Окружение бэкенда не пробрасывается в скиллы в Docker-режиме

**Симптом:** после фикса `requests` `tv-control` всё равно падал —
`config.py` бросал `RuntimeError("missing OPENROUTER_API_KEY or SUMMARIZATION_LLM_API_KEY env var")`.

**Причина:** `SkillCommandExecutor.execute()` инжектит только
`SOUZ_SKILL_ID` / `SOUZ_SKILL_ROOT` / `SOUZ_SKILL_SUPPORTING_FILES` / `SOUZ_TOOL_BRIDGE_SOCK`
плюс аргумент `environment` от модели. В LOCAL-режиме скилл — дочерний процесс `ProcessBuilder`
и наследует **всё** окружение бэкенда, включая все секреты (`SOUZ_MASTER_KEY`,
`SOUZ_BACKEND_DB_PASSWORD`, OAuth client secrets, Codex-токены). Скиллы на этот слив подсели.
`docker exec` не передаёт ничего из этого.

Отдельно: имя env-переменной в скилле дрейфануло — `config.py` читал `OPENROUTER_API_KEY` /
`SUMMARIZATION_LLM_API_KEY`, а на этом деплое тот же OpenRouter-ключ (`sk-or-…`, длина 73)
лежит под именем `OPENAI_SUMMARIZATION_API_KEY` — то есть `tv-control` был сломан и в
LOCAL-режиме здесь, просто его не дёргали.

**Воркэраунд:**
- Правка `tv-control/scripts/config.py`: lookup ключа + текст ошибки теперь пробуют и
  `OPENAI_SUMMARIZATION_API_KEY` (старые имена оставлены как fallback). Применено в трёх
  местах: локальный зеркальный каталог пользователя
  `/Users/jiles/Projects/souz-vps-skills/skills/tv-control/scripts/config.py`; канонический
  `/root/.local/state/souz/skills/tv-control/scripts/config.py` на VPS (записан в
  существующий inode через `cat >file`, чтобы хардлинк-копии в песочнице тоже обновились);
  копия `/srv/souz-skills/vacherevkov/tv-control/scripts/config.py`.
- Оба sandbox-контейнера пересозданы вручную с точным spec (имя / mount / `--user 0:0` /
  `sleep`) **плюс `--env OPENAI_SUMMARIZATION_API_KEY=<значение>`**. `docker exec` наследует
  окружение контейнера, а бэкенд ходит `exec` по *имени* контейнера — так что без правки кода
  и без рестарта контейнер подхватывается.

**Оговорка:** руками созданные контейнеры (и их env) слетают при любом рестарте бэкенда или
`docker rm`.

**Правильный фикс (Phase 1.5) — сделан:** `SkillCommandExecutor` в DOCKER-режиме
пробрасывает курируемый allowlist из host-env. Список имён задаётся `SOUZ_SANDBOX_FORWARD_ENV`
в `backend.env` (по умолчанию — `OPENAI_SUMMARIZATION_{API_KEY,BASE_URL,MODEL}`), резолвится
`resolveForwardedSandboxEnv(System.getenv())` один раз на бусте и инжектится нижним слоем
precedence (не перекрывает `SOUZ_SKILL_*` / мост / `environment` от модели). LOCAL не трогаем —
там `ProcessBuilder` и так наследует всё. Контейнерный `--env`-воркэраунд больше не нужен.

### 3.4 Отравление бандла скилла файлом `.pyc`

**Симптом:** после 3.3 `tv-control` снова стал «недоступен». Лог:
`Failed to read loose skill bundle ... Disallowed skill file extension for scripts/__pycache__/config.cpython-311.pyc`.

**Причина:** проверочный `python3 -c 'import config'`, запущенный внутри контейнера, записал
`scripts/__pycache__/*.pyc` в bind-mount каталог скилла, а
`FileSystemSkillRegistryRepository` **жёстко отвергает любой бандл с `.pyc`**. `run.py`
защищается через `sys.dont_write_bytecode = True`; ad-hoc импорты — нет.

**Воркэраунд:** удалён лишний `__pycache__` из обоих sandbox-деревьев; контейнеры пересозданы
с `--env PYTHONDONTWRITEBYTECODE=1`, чтобы случайный импорт не отравил снова.

**Правильный фикс (Phase 1.5):** ставить `PYTHONDONTWRITEBYTECODE=1` в образе песочницы по
умолчанию, и/или чтобы реестр игнорировал `__pycache__` / `.pyc`, а не ронял весь бандл.

### 3.5 Мост скилл→тул не биндится — `AF_UNIX path too long`

**Симптом:** `tv-control` (объявляет `souz.bridge-tools: "device.mcp.call_tool"`) падал;
`bridge.py` сообщал, что `SOUZ_TOOL_BRIDGE_SOCK` не задан. Прямой тест bind:
`OSError: AF_UNIX path too long`.

**Причина:** `SkillCommandExecutor` (ветка DOCKER) кладёт сокет моста в
`<sandbox hostRoot>/state/.br/<10hex>.sock`. На этом боксе это
`/root/.local/state/souz/runtime-sandboxes/docker/<base64url(userId)=48 симв>/state/.br/<id>.sock`
= **123 символа**, сверх лимита `sockaddr_un.sun_path` (108 с учётом NUL). `bind()` кидает
исключение → `RunSkillCommand` падает. Это конкретная форма комментария «KNOWN LIMITATION»,
который уже есть в `SkillCommandExecutor.kt` — там боялись границы Docker-in-VM (ECONNREFUSED
на macOS/colima); на нативном Linux реальный блокер — длина пути из-за глубокого per-user
корня песочницы. Сырой UDS поверх bind-mount тут работает, как только путь достаточно
короткий (проверено ~87–95 символов).

**Воркэраунд:** перенести корень песочницы на короткий путь и оставить симлинк:

```
mv /root/.local/state/souz/runtime-sandboxes  /s/rs
ln -s /s/rs  /root/.local/state/souz/runtime-sandboxes
```

`DockerSandboxLayout` прогоняет `hostRoot` через `toRealPath()`, поэтому `layout.hostRoot`
резолвится в `/s/rs/docker/<base64>`, и путь сокета падает до ~87 символов. Симлинк лежит
*выше* `hostManagedRoots`, так что `check(!isSymbolicLink)` в `ensureHostDirectories()` не
срабатывает; `requireMatchingHostRoot` сравнивает realpath — контейнеры по-прежнему
совпадают. Оба контейнера пересозданы с bind-mount короткого `src=/s/rs/docker/<base64>`,
затем `systemctl restart souz-backend`, чтобы сбросить закешированную песочницу со старым
длинным путём.

**Правильный фикс (Phase 1.5):** укоротить `SandboxScope.storageKey()` до короткого хеша
вместо base64 от полного userId, либо биндить сокет моста в отдельном коротком хостовом
каталоге, примонтированном в контейнер.

---

## 4. Операционные грабли

- **Пересборка образа требует рестарта бэкенда.** `FactoryBackedToolInvocationRuntimeSandboxResolver`
  кеширует один `DockerRuntimeSandbox` на scope без eviction (`// TODO: implement cache
  eviction`), а `DockerSandboxCommandExecutor.execute()` не зовёт `ensureStarted()` перед
  `docker exec`. После `docker rm -f` контейнера каждая skill-команда молча падает с «no such
  container», пока не сделаешь `systemctl restart souz-backend`. CD уже делает рестарт, так
  что затрагивает только ручные пересборки.
- **Тихие ненулевые exit'ы.** Упавшая skill-команда (`ModuleNotFoundError`, отсутствие env,
  сбой bind моста) — это ненулевой exit `docker exec`, отданный только модели; в логе бэкенда
  **ни WARN, ни ERROR**. `journalctl -p warning` показывал «No entries» для нескольких сбоев
  выше. Только отказ по `.pyc` дал WARN.
- **Idle-контейнер почти бесплатен** — keepalive `sleep` это ~800 KiB / 2 PID. Цена
  появляется, только когда внутри реально что-то тяжёлое (актуально, когда зайдёт Lightpanda).
- **Stale-контейнеры переживают деплои** (нет eviction) — иногда прибирать руками:
  `docker rm -f $(docker ps -aq --filter name=souz-runtime)` и следом рестарт бэкенда.

---

## 5. Про безопасность

До этого изменения LOCAL-режим + root systemd-юнит означали, что shell / файловые /
skill-скрипты агента исполнялись **как root на хосте**, а каждый skill-подпроцесс наследовал
всё окружение бэкенда (все секреты). Docker-режим локализует и то, и другое. Поэтому
passthrough env из Phase 1.5 (§3.3) обязан быть узким allowlist, а не возвратом слива.

---

## 6. Что нужно для «Phase 1 done» (кодовая Phase 1.5)

1. Миграция хранилища скиллов в per-user Docker-деревья, либо общий bind-mount скиллов (§3.1).
2. Стратегия runtime-зависимостей скиллов (§3.2).
3. ~~Курируемый allowlist-passthrough env в `SkillCommandExecutor`~~ — сделано, `SOUZ_SANDBOX_FORWARD_ENV` (§3.3).
4. `PYTHONDONTWRITEBYTECODE=1` в образе и/или толерантность реестра к `__pycache__`/`.pyc` (§3.4).
5. Короткий хеш `SandboxScope.storageKey()` либо отдельный короткий каталог для сокета моста (§3.5).
6. Лимиты ресурсов контейнера (`--memory` / `--cpus` / `--pids-limit`) в
   `DockerContainerHandle.createAndStart` — отложено в Phase 1 (мало юзеров, нет давления по
   памяти на VPS), но нужно до более широкого использования.
7. Eviction контейнеров в `FactoryBackedToolInvocationRuntimeSandboxResolver`.
8. Более громкое логирование ненулевых выходов skill-команд.
9. Ротация логов `/etc/docker/daemon.json` (`json-file` `max-size` / `max-file`) — отложено,
   чтобы не баунсить Docker-демон (и соседний контейнер `hindsight`) на общем боксе.

Когда всё это влито и выкатано через CD, ручные воркэраунды из §2–§3 и симлинк `/s/rs` можно
убрать.

---

## 7. Откат

Вернуть бэкенд к host-local исполнению тулов:

```
# убрать строку SOUZ_SANDBOX_MODE (или восстановить бэкап) и перезапустить
sed -i '/^SOUZ_SANDBOX_MODE=/d' /opt/souz-backend/config/backend.env
systemctl restart souz-backend
# опционально: снести sandbox-контейнеры
docker rm -f $(docker ps -aq --filter name=souz-runtime)
```

Перенос `/s/rs` и симлинк `runtime-sandboxes` в LOCAL-режиме безвредны (этот путь никто не
читает) — можно оставить или откатить через `rm <симлинк> && mv /s/rs <оригинал>`.

---

## 8. Phase 2 — Lightpanda в образе

Статус: **бинарь в образе, дымовые тесты пройдены. Агентного тула нет.**

Добавлено в `sharedLogic/Dockerfile` после apt-шага:

```dockerfile
ARG LIGHTPANDA_URL=https://github.com/lightpanda-io/browser/releases/download/nightly/lightpanda-x86_64-linux
RUN curl -fsSL -o /usr/local/bin/lightpanda "$LIGHTPANDA_URL" \
    && chmod +x /usr/local/bin/lightpanda \
    && /usr/local/bin/lightpanda version
```

- Канал только **nightly** (стабильных релизов у апстрима нет). Тег `nightly` — движущаяся
  цель; версия на момент интеграции: `1.0.0-nightly.9142+cb26e0553`.
- Бинарь ~168 МБ, ELF x86-64, динамически слинкован с glibc — совместим с базой
  `node:22-bookworm` (на musl-образах не запустится). Не stripped (в нём debug_info).
- Размер образа: **1.62 ГБ → 1.85 ГБ**.
- CLI: `lightpanda version | fetch | serve | mcp | run | agent`.

Дымовые тесты на VPS (все прошли, через `docker run --rm`):

| Тест | Результат |
|---|---|
| `lightpanda version` | `1.0.0-nightly.9142+cb26e0553` |
| `fetch --dump html https://example.com` | вернул отрендеренный DOM |
| `--cookie-jar` → `--cookie` round-trip (httpbingo `/cookies/set` → `/cookies`) | кука (в т.ч. `httpOnly:true`) сохранена в JSON, перезагружена и реально отправлена обратно |
| `serve` (CDP WebSocket сервер) | биндится на `127.0.0.1:9222`, `/json/version` отдаёт `webSocketDebuggerUrl` |
| `python3 -c "import requests"` / `node --version` | не сломались |

Полезное для Phase 3 (обнаружено в `lightpanda help fetch|serve`):

- `fetch --dump` умеет `html | markdown | semantic_tree | semantic_tree_text | png` —
  `semantic_tree_text` / `markdown` это готовые представления страницы для LLM.
- Подкоманда `mcp` — MCP-сервер (stdio **или** HTTP): путь MCP-over-stdio для моста в Phase 3
  встроен в бинарь.
- Персистентность: `--cookie-jar`/`--cookie` (куки) + `--storage-engine sqlite`
  `--storage-sqlite-path <файл>` (localStorage и пр.) — частично закрывает пробел «Lightpanda
  не хранит состояние на диске».
- SSRF/egress уже из коробки: `--block-private-networks`, `--block-cidrs`, `--block-urls`,
  `--http-proxy`, `--adblock-lists`.
- `serve` умеет и `--protocol webdriver` помимо `cdp`.

Два уже работающих sandbox-контейнера бэкенда **не пересоздавались** — они на старом образе,
Lightpanda-тула всё равно нет. Следующий контейнер, который создаст бэкенд, возьмёт новый
образ; при добавлении браузерного тула в Phase 3 контейнеры пересоздадутся штатно.

---

## 9. Phase 3 — браузерный тул

Статус: **тул `browser` написан, задеплоен, живой end-to-end прогон через агента подтверждён
2026-09-03** — «открой ya.ru через браузер» → `RunSkillCommand(skillId="browser",
{action:"navigate", url:"https://ya.ru"})` → `ToolBrowser` → `docker exec agent-browser open …`
→ `snapshot -i` вернул ref-дерево JS-рендеренной главной Яндекса, агент описал.
Наблюдение: с датацентрового IP (KZ) ya.ru редиректит на `yandex.kz` — браузер видит
гео-локализованную версию, не то что видит пользователь.

Целевой сценарий — «действовать за меня» (логины, формы, многошаговые флоу с паузой на
SMS-код). Research-чтение через браузер не цель (обычный fetch справляется), кроме
JS-страниц, которые fetch не берёт (ya.ru, wildberries.ru).

### 9.1 Что выяснили экспериментами

- `lightpanda serve` **сбрасывает всю страницу/DOM/JS/куки** в момент разрыва CDP-соединения.
  Состояние живёт только пока одно соединение держится открытым (проверено: переживает 40–45 с
  простоя за 30-с watchdog на удержанном соединении).
- Значит нужна прослойка, которая держит одно CDP-соединение всю жизнь контейнера.

### 9.2 Прослойка: `agent-browser` (Vercel), не свой шим

Сначала написали свой Node-шим (`chrome-remote-interface` + HTTP на `127.0.0.1:9223`,
удерживает CDP через flatten-сессии). Работал, но ~62 МБ RSS (baseline Node).

Заменили на **Vercel `agent-browser`** (`npm i -g agent-browser@0.27.0`, Rust CLI+daemon,
~9 МБ RSS): daemon держит одно CDP-соединение, `docker exec <c> agent-browser <cmd>` —
состояние持ится между вызовами; accessibility-снапшот с `@eN` ref'ами; словарь
`open/click/fill/type/press/select/snapshot/get/eval/wait/back` (близко к Playwright MCP);
встроенный `AGENT_BROWSER_IDLE_TIMEOUT_MS` (idle-eviction daemon'а).

Нюанс: **автозапуск Lightpanda у agent-browser сломан** на текущем nightly (передаёт
`serve --timeout`/`--storage-engine`, которые nightly отвергает). Обход: `serve` поднимаем
сами, `agent-browser connect <port>`.

Файлы в образе (`sharedLogic/`):
- `Dockerfile`: `npm i -g agent-browser@0.27.0` (без `agent-browser install` — тот тянет Chrome).
- `docker/browser-supervisor.sh`: поднимает `lightpanda serve` (нашими флагами
  `--cookie`/`--cookie-jar`), затем `agent-browser connect`; рестарт serve + reconnect при
  падении. `SOUZ_SANDBOX_BROWSER=0` выключает.
- `docker/entrypoint.sh`: запускает supervisor под мини-loop.
- `--init` в `docker run` (для reaping; в CD-пути нужен флаг в `DockerContainerHandle` — TODO).
- Удалён предыдущий `docker/browser-shim.js`.

Проверено на пересозданных контейнерах (через `docker exec agent-browser`):
`open` → `snapshot -i` (ref-дерево) → `click @ref` **навигирует** (со свежим ref — устаревший
ref молча no-op и врёт `✓ Done`, поэтому snapshot перед каждым ref-действием) → отдельный
`get url` показывает page 2; `eval window.__x=…` переживает отдельные вызовы; `cookies get --json`
(с httpOnly), `storage local`, `state save <file>` работают. Футпринт: `serve` ~20–32 МБ +
daemon ~9 МБ = **~30–40 МБ/юзер**.

Известные ограничения:
- `serve` не принимает `--storage-engine` (в `help serve` есть, реализовано только у `fetch`)
  → localStorage только в живой сессии, куки — через `--cookie-jar`, localStorage на диск не
  переживает рестарт serve/контейнера.
- `--cookie-jar` флашится только при graceful exit; `docker rm -f` (SIGKILL) теряет.
- Диск-персистентность плана: периодический `agent-browser state save` в
  `/souz/state/browser/` + restore через `cookies set`/eval после `connect` (не реализовано).

### 9.3 Тул `browser` (бэкенд)

- `ToolBrowser` — `sharedLogic/src/jvmMain/.../tool/browser/ToolBrowser.kt`. Один нативный
  тул `browser`, `action` ∈ `snapshot | navigate | click | type | read | back | reset`.
  Проксирует `agent-browser` через `SandboxCommandExecutor` (`PROCESS`-команда → `docker exec`).
  DOCKER-only (в LOCAL возвращает ошибку). Каждое page-changing действие возвращает свежий
  `snapshot -i`. `navigate` требует http(s); `ref` валидируется как `eN`.
- Проводка: `RuntimeToolsModule.kt` (jvmMain, backend-only) → `RuntimeToolsFactory` под
  `ToolCategory.BROWSER`.
- Экспозиция: `ToolCategory.BROWSER` добавлен в `BACKEND_SAFE_TOOL_CATEGORIES`
  (`backend/.../common/BackendSafeToolCatalog.kt`) — иначе тул фильтровался и не попадал в
  `availableToolNames` → не рекламировался.
- Бэкенд-агент — `SkillsGraphBasedAgent`: модель не видит `browser` напрямую, а находит его
  в skill-инвентаре (`NodesSkillInventory` перечисляет все категории) и вызывает как
  `RunSkillCommand(skillId="browser", arguments={action:…, …})`.
- Первый живой тест («открой ya.ru и опиши»): модель выбрала `WebPageText` (обычный fetch,
  тоже подан как скилл), а не `browser` → на JS-SPA пусто. Поправили описание `browser`:
  теперь явно про JS-страницы и «когда fetch/WebPageText вернул пусто». Повторный тест
  заблокировался сбоем OpenAI.

### 9.4 Не сделано / открыто

- **Закреплённый `enabledTools`** (решено вручную, нужен нормальный фикс): новый бэкенд-тул
  не доезжает до юзеров, у которых в `user_settings.settings_json` сохранён явный список
  `enabledTools` (`EffectiveSettingsResolver.normalizeEnabledTools`: `persisted ?: supported`).
  Тестовым юзерам добавили `"browser"` в список руками (`jsonb_set` + рестарт). Правильно —
  при добавлении тула union-ить его во все закреплённые списки, либо sentinel «все тулы».
- Роутинг: убедиться, что «открой сайт» уверенно уходит на `browser` (классификатор
  `NodesClassification` BROWSER-описание сейчас desktop-tab-флейвора).
- Egress-политика: `--block-private-networks` + CIDR-денилист в `browser-supervisor.sh` (не добавлено).
- Редакция текста `type` в логах; confirm-гейт на необратимые клики; лок «один браузер на юзера».
- Диск-персистентность сессии (`state save`/restore в supervisor).
- `--init` в `DockerContainerHandle.createAndStart` для CD-пути.
- Node-шим удалён, но если решим ужимать `agent-browser` (~9 МБ и так немного) — вариант был Go.

### 9.5 Инцидент деплоя (2026-09-03)

Задеплоил сначала чистый `main` — прод крутил `feat/agent-step-narration` (brave + narration).
Юзеры с полем `narrateSteps` в Postgres-настройках → `500` (`UnrecognizedPropertyException`),
т.к. на `main` поля нет. Починка: перенёс browser-изменения на `feat/agent-step-narration`
(`git stash -u` → checkout → pop, конфликтов ноль) и задеплоил оттуда. `/v1/bootstrap` → 200.
Вывод: **деплоить надо с ветки, которую реально крутит прод, не с чистого `main`.**

---

## 10. Решённая архитектура (2026-09-04): Lightpanda по умолчанию, без попыток притвориться

Статус: **действующая конфигурация.** Всё, что ниже в §10.1–10.3, — краткая история того, что
пробовали и почему отказались, для контекста решения. Сама реализация — в §10.4.

### 10.1 Что пробовали вместо Lightpanda и почему не взяли

**google-chrome-stable, headful под Xvfb** (эволюция: chrome-headless-shell → Chrome for
Testing → google-chrome-stable `--headless=new` → headful под Xvfb). Довели фингерпринт до
состояния, когда Wildberries — самая жёсткая цель — проходил E2E: настоящий бренд «Google
Chrome», консистентный `userAgentData`, чистые TLS/JA3/JA4, реальный экран из Xvfb, нативные
`navigator.plugins`. Цена: **~1.3–1.5 ГБ RAM на сессию**, по факту одна параллельная сессия на
этом VPS. И даже это не гарантия: WebGL/canvas всё равно software-рендер (нет GPU) — тавр для
DataDome-класса; а капча (свой слайдер Ozon, Yandex SmartCaptcha на входе ВкусВилл/Aviasales)
появляется **независимо от качества фингерпринта** — даже настоящий резидентный Chrome от
Bright Data Scraping Browser (см. ниже) получил тот же слайдер Ozon.

**Bright Data Web Unlocker / Scraping Browser.** Web Unlocker как `--proxy` для живого браузера
не работает в принципе — его разлочка происходит только когда он сам целиком забирает URL
(curl-стиль); разлоченная сессия остаётся в его браузере, к нашему не переходит. Scraping
Browser (настоящий resident-IP Chrome по CDP) реально driвился через самодельный
auth-инжектирующий шим (`agent-browser` не шлёт basic-auth на wss) — но после **первой**
навигации CDP-соединение демона рвалось и все следующие `Page.navigate` виснут по таймауту;
многошаговые сценарии (логины) так не работают. Плюс биллинг per-GB (~$8/ГБ) плохо ложится на
модель «держим браузер на юзера долго».

**Вывод по обеим веткам:** хороший фингерпринт — необходимое, но не достаточное условие; капча
на защищённых сайтах (Ozon, Yandex-логины) — отдельная стена, которую качество браузера не
снимает, а решает либо солвер, либо человек в цикле. Тратить ресурсы (деньги, RAM, инженерное
время) на бесконечную гонку за идеальным фингерпринтом того не стоит.

### 10.2 Почему Lightpanda не может притвориться браузером — принципиально, не баг

Проверено напрямую через CDP (`Emulation.setUserAgentOverride`, CLI `--user-agent`): **любое
значение UA, содержащее "Mozilla", молча отклоняется** — и на уровне CLI, и на уровне CDP. Это
не забытая фича, а сознательная политика проекта с собственными unit-тестами апстрима
(`cdp.Emulation: setUserAgentOverride ignores mozilla`, см. `src/server/cdp/domains/emulation.zig`
в [lightpanda-io/browser](https://github.com/lightpanda-io/browser)). Форков, которые это
снимают, в индексе GitHub нет — только зеркала с тем же кодом. Реальные исходящие заголовки
после любых попыток override:

```
User-Agent: Lightpanda/1.0
Sec-Ch-Ua: "Lightpanda";v="1"
Sec-Ch-Ua-Full-Version-List: "Lightpanda";v="1.0.0-nightly.…"
```

и в JS `navigator.userAgent === "Lightpanda/1.0"`. Обойти подменой `navigator.userAgent` через
свой injected-скрипт технически можно (`Page.addScriptToEvaluateOnNewDocument` реально работает
в Lightpanda) — но тогда JS будет врать «настоящий браузер», а сетевой заголовок и Client Hints
честно кричат «Lightpanda» на любой server-side проверке. Это хуже, чем HeadlessChrome: там хотя
бы выглядело как настоящий браузер с ошибкой, здесь — прямая, однозначная сигнатура автоматизации
от самого движка. **Решение: не пытаться.** Lightpanda используется как то, чем он честно
является.

### 10.3 Что это значит на практике

Lightpanda не пройдёт ни один сайт, который вообще смотрит на UA/Client-Hints/фингерпринт —
то есть сайты с серьёзной анти-бот защитой (WB, Ozon, Yandex-логины) вне досягаемости
принципиально, при любом качестве нашей обвязки. Это ожидаемо и осознанно — `browser`
позиционируется для обычных сайтов и JS-страниц, которые не берёт обычный `fetch`, а не как
инструмент обхода антифрода. Для защищённых сайтов нужен отдельный, осознанно спроектированный
путь (солвер под конкретный тип капчи, либо человек в цикле) — вне рамок этого тула.

### 10.4 Реализация

Образ (`sharedLogic/Dockerfile`) вернулся к лёгкому виду: убраны google-chrome-stable, Xvfb,
`chrome-wrapper.sh`, `stealth-init.js`, `brd-cdp-shim.js` + `ws`. Эти три файла также удалены из
`sharedLogic/docker/` — ни Lightpanda, ни managed browser-инфра уровня Browserbase/Kernel (если
до неё дойдёт) в них не нуждаются; они не были закоммичены, так что при необходимости их придётся
писать заново, а не восстанавливать. Образ
**1.62 ГБ → 2.04 ГБ** (рост от `node:22-bookworm`/npm-кеша со времени первого замера, не от
чего-то нового) вместо **2.93–2.95 ГБ** с Chrome; в рантайме Lightpanda ~30–40 МБ на сессию
против 1.3–1.5 ГБ у Chrome.

- `docker/browser-supervisor.sh`: сами поднимаем `lightpanda serve --host 127.0.0.1 --port 9222
  --cookie-jar …` (автозапуск Lightpanda у `agent-browser` по-прежнему ломается о флаги, которые
  этот nightly отвергает) и один раз прогреваем демон `agent-browser` (`open about:blank`).
  Проверено: `--storage-engine`/`--storage-sqlite-path`, хоть и упомянуты в `serve --help` как
  «common options», на рантайме дают `UnknownOption` именно для `serve` — убраны.
- **Уточнение по `--cookie-jar`:** проверено прямым тестом (кука выставлена → `SIGTERM`/`SIGINT`
  процессу → файл кук не создаётся) — **флаш на сигнальное завершение не происходит вообще**, не
  только на `SIGKILL`, как думали раньше. То есть на практике всё состояние (куки, DOM, JS,
  localStorage) — только в памяти процесса `serve`, и переживает исключительно вызовы внутри
  одного и того же контейнера, пока и `lightpanda serve`, и демон `agent-browser` живы. Рестарт
  или пересоздание контейнера = разлогинивает все сессии всех сайтов. Обработчик `TERM`/`INT` в
  supervisor оставлен ради корректного завершения процесса (не оставлять сирот), не ради
  персистентности.
- `docker/entrypoint.sh` упрощён до одного пути: пишет `AGENT_BROWSER_CONFIG`
  (`{"cdp":"9222"}`), поднимает supervisor-цикл. Ветки под Chrome/Bright Data убраны.
- `AGENT_BROWSER_ENGINE=lightpanda` в ENV — для ясности; фактически `agent-browser` подключается
  к уже поднятому `serve` через `AGENT_BROWSER_CONFIG`, не запускает Lightpanda сам.
- Проверено на пересобранном образе: честная идентификация (`User-Agent: Lightpanda/1.0`),
  состояние (`window.__marker`) переживает отдельные `docker exec agent-browser …` вызовы —
  свойство «браузер на юзера, не на разговор» подтверждено на новой архитектуре.
- `ToolBrowser.kt` (код тула не менялся — он engine-agnostic) и его `description` для модели
  обновлены: явно говорит «честный автоматизированный клиент, не притворяется», просит не
  повторять действие при капче/блокировке, а сообщить об этом прямо.

# Баг: скоуп, который провайдер не выдаёт, навсегда вешает скилл в reconnect-петлю

Статус: **подтверждён на проде 2026-09-04** (провайдер `huawei`, скилл `huawei-health`).
Не исправлен — ниже разбор, воспроизведение и варианты фикса.

## Симптом

Скилл с OAuth никогда не начинает работать. Каждый вызов `SafeApiCall` возвращает
`reconnectRequired: true`, пользователь идёт по ссылке, успешно подтверждает доступ
у провайдера — и следующий вызов снова просит переподключиться. Бесконечно.

Со стороны пользователя это выглядит как «скоуп не регистрируется» или «конфликт
учёток»: авторизация проходит, но эффекта нет. Ни в логах бэкенда, ни в чате нет
ничего похожего на ошибку — `reconnectRequired` это штатный, ожидаемый результат
вызова, а не сбой.

## Причина

`SkillOAuthGatewayImpl.call` требует, чтобы **все** скоупы из манифеста скилла
входили в выданные провайдером
([SkillOAuthGatewayImpl.kt:100-108](../skill-oauth-impl/src/main/kotlin/ru/souz/skilloauth/impl/SkillOAuthGatewayImpl.kt#L100-L108)):

```kotlin
val grantedScopes = credential.grantedScopes.toSet()
val missingScopes = requiredScopes - grantedScopes
if (missingScopes.isNotEmpty()) {
    return reconnectRequired(...)
}
```

`requiredScopes` — это ровно `oauthScopes` из `SKILL.md`
([ToolSafeApiCall.kt:109](../sharedLogic/src/commonJvmMain/kotlin/ru/souz/tool/skills/ToolSafeApiCall.kt#L109)).

Проверка исходит из того, что непокрытый скоуп — состояние временное, лечится
повторной авторизацией. Но у большинства провайдеров скоуп нужно **отдельно
одобрить для приложения** (у Huawei — заявка в консоли разработчика). Пока
одобрения нет, провайдер не выдаст скоуп, сколько ни переподключайся. Условие
становится невыполнимым, а петля — вечной: система бесконечно предлагает
пользователю средство, которое в принципе не может помочь.

Ключевой момент: **провайдер при этом не обязан возвращать ошибку.** Huawei на
запрос из 9 скоупов, из которых 2 не одобрены, спокойно отдаёт токен с 7
выданными. То есть авторизация с точки зрения OAuth успешна, credential
записывается — и падает уже наша собственная проверка полноты.

### Усугубляющий фактор: накопленный union скоупов

`skill_oauth_requested_scopes`
([V1__skill_oauth.sql:45](../skill-oauth-impl/src/main/resources/db/migration-skill-oauth/V1__skill_oauth.sql#L45))
хранит объединение всех когда-либо запрошенных скоупов для пары
`(user_id, provider)`, и `startAuthorization` расширяет каждый новый запрос до
него ([SkillOAuthGatewayImpl.kt:175-201](../skill-oauth-impl/src/main/kotlin/ru/souz/skilloauth/impl/SkillOAuthGatewayImpl.kt#L175-L201)).

Поэтому **убрать скоуп из `SKILL.md` недостаточно**: пока union жив, ссылка
авторизации продолжает просить и удалённый скоуп тоже.

Union не вечен — он «протухает» через `PENDING_STATE_TTL_SECONDS` = 600 секунд
([PostgresSkillOAuthPendingStateRepository.kt:82](../skill-oauth-impl/src/main/kotlin/ru/souz/skilloauth/impl/PostgresSkillOAuthPendingStateRepository.kt#L82)):

```kotlin
val baseScopes = if (updatedAt.isBefore(activeSince)) emptyList() else existingScopes
```

Но каждая попытка обновляет `updated_at = now`. Пользователь, который упорно
переподключается (а именно это он и делает, когда не работает), сам продлевает
окно и не даёт union протухнуть. Практически: **чтобы union сбросился, надо 10
минут вообще ничего не трогать.**

Для провайдеров, которые на неизвестный или неодобренный скоуп отвечают ошибкой
(`invalid_scope`), а не молча урезают выдачу, тот же union ломает авторизацию
жёстко — не выдаётся вообще ничего.

### Смежная проблема в `handleCallback`

[SkillOAuthGatewayImpl.kt:288](../skill-oauth-impl/src/main/kotlin/ru/souz/skilloauth/impl/SkillOAuthGatewayImpl.kt#L288):

```kotlin
grantedScopes = tokenResult.scopes.ifEmpty { pending.requestedScopes }
```

Поле `scope` в ответе на обмен кода по RFC 6749 необязательное. Если провайдер
его не вернёт, мы запишем как выданные **запрошенные** скоупы — включая те, что
не выдавались. Тогда баг перевернётся: петли не будет, но `SafeApiCall` пойдёт
в API с токеном без нужных прав и получит 403, а состояние в базе будет врать.

## Как воспроизвести

Нужен провайдер, у которого есть скоуп, требующий отдельного одобрения, и
приложение, для которого он не одобрен.

1. Завести в `oauth-providers.json` провайдера (или взять существующего).
2. Прописать `<NAME>_OAUTH_CLIENT_ID/_CLIENT_SECRET/_REDIRECT_URI` в env бэкенда.
3. Положить скилл, у которого в `SKILL.md` в `oauthScopes` есть скоуп, **не
   одобренный** для приложения на стороне провайдера, вперемешку с одобренными.
4. Попросить агента сделать что-нибудь этим скиллом → `SafeApiCall` вернёт
   `reconnectRequired` с `authorizationUrl`.
5. Пройти по ссылке и подтвердить доступ. Провайдер выдаст токен только с
   одобренными скоупами.
6. Повторить шаг 4.

**Ожидается:** внятное сообщение, что скоуп недоступен для этого приложения и
переподключение не поможет.
**Фактически:** снова `reconnectRequired` с той же ссылкой. Шаги 4-5 зацикливаются.

Проверить состояние:

```sql
select user_id, provider, granted_scopes from skill_oauth_credentials where provider = '<p>';
select user_id, provider, requested_scopes from skill_oauth_requested_scopes where provider = '<p>';
```

В `requested_scopes` будет неодобренный скоуп, в `granted_scopes` — нет.

### Что было на проде

Union накопил 9 скоупов, включая `oxygenSaturation.read` и `bodyTemperature.read`,
которые Huawei для приложения `118875305` не выдаёт. Credential от 20:47 при этом
содержал 6 скоупов + `openid` — то есть авторизация проходила, а проверка полноты
в `call` валилась, пока оба скоупа не были убраны из `SKILL.md`.

## Как чинить сейчас (обходной путь)

1. Убрать неодобренный скоуп из `oauthScopes` в `SKILL.md`.
2. Снести накопленный union, иначе ссылка продолжит его просить:

```sql
delete from skill_oauth_pending_states   where provider = '<p>';
delete from skill_oauth_credentials      where provider = '<p>';
delete from skill_oauth_requested_scopes where provider = '<p>';
```

Рестарт бэкенда не нужен: `SkillOAuthGatewayImpl` ничего не кеширует, каждый
вызов идёт в Postgres. Альтернатива пункту 2 — 10 минут не трогать авторизацию,
чтобы union протух сам.

## Что стоит починить в коде

- **Не расширять запрос до union безусловно.** Пересекать накопленный union с
  текущими `oauthScopes` манифеста: скоуп, который скилл больше не просит, не
  должен воскресать из истории.
- **Различать «ещё не выдан» и «не будет выдан».** Если после успешного
  колбэка запрошенный скоуп так и не появился в `granted_scopes` — это отказ
  провайдера, а не повод предлагать ту же ссылку снова. Помечать такой скоуп и
  возвращать модели внятную причину вместо `reconnectRequired`.
- **Дать способ сбросить состояние без SQL** — флаг у `ConnectOAuthProvider`
  или отдельный инструмент; сейчас единственный выход это доступ к проду.
- **Не записывать запрошенные скоупы как выданные** в `handleCallback`, когда
  провайдер не вернул `scope` (см. смежную проблему выше).
- Рассмотреть деление `oauthScopes` на обязательные и опциональные: сейчас один
  недоступный скоуп кладёт весь скилл, хотя остальные метрики работали бы.

## Связанное

- `docs/backend-docker-sandbox-rollout.md` — раскладка скиллов в DOCKER-режиме
  (правки `SKILL.md` на проде надо писать в тот же inode, иначе копии в
  песочницах пользователей останутся старыми).

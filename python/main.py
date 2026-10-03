"""
Клиент API «Стандартизация адреса» Atlorium — разбор произвольной адресной строки
в структуру: страна / город / улица / дом / квартира + нормализованное написание
и коэффициент качества разбора.

Запуск (работает сразу, без регистрации — на демо-ключе):
    pip install -r requirements.txt
    python main.py
    python main.py "мск ленинскй проспект д4 кв5"

Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
ATLORIUM_API_KEY. Код при этом не меняется.
"""

import os
import sys
import time
from dataclasses import dataclass, field

import requests

# Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальным
# разбором) — чтобы можно было встроить и протестировать интеграцию до оплаты.
# Ответы детерминированы: одна и та же строка всегда даёт один и тот же результат,
# поэтому на них можно писать стабильные тесты.
#
# ВАЖНО ПРО ПЕСОЧНИЦУ: мок НЕ разбирает вашу строку. Он генерирует случайный
# правдоподобный адрес и всегда ставит quality = 100. Порог качества ниже на моках
# не срабатывает — он заработает только на боевом ключе. Подробно — в README.
SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1"

API_KEY = os.environ.get("ATLORIUM_API_KEY", SANDBOX_KEY)
BASE_URL = os.environ.get("ATLORIUM_BASE_URL", "https://atlorium.com")

TIMEOUT = 30

# Пауза перед повтором после 429 и число повторов.
RETRY_DELAY = 20
MAX_RETRIES = 1

# Потолок ожидания. Исчерпав ЧАСОВОЕ окно, сервер честно просит подождать десятки
# минут — и клиент, слепо доверяющий заголовку Retry-After, «зависнет» на всё это
# время (а в CI просто съест бюджет джоба). Дольше потолка не ждём: честно сообщаем,
# что квота исчерпана, и выходим.
MAX_RETRY_DELAY = 120

# ── Порог качества: главная настройка всего сценария ──────────────────────────
# quality — коэффициент качества разбора, 0..100. 100 — идеальный разбор без лишних
# фрагментов; ≥90 — нормально; ниже 80 — к результату стоит относиться осторожно.
#
# Смысл порога: молча записать криво разобранный адрес в базу ХУЖЕ, чем отправить
# его человеку. Уверенные случаи автоматизируем, сомнительные эскалируем.
QUALITY_THRESHOLD = 80


class AtloriumError(RuntimeError):
    """Ошибка API. Код HTTP разложен в человекочитаемую причину."""

    REASONS = {
        400: "Строка не указана или из неё не удалось выделить адрес (запрос НЕ тарифицируется)",
        401: "API-ключ отсутствует, просрочен или недействителен",
        402: "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429: "Превышен лимит запросов — повторите позже",
        501: "Комбо-режим (bind — привязка к ГАР, geocode — координаты) ещё не реализован",
        503: "Сервис временно недоступен (плановые работы) — повторите позже",
    }

    def __init__(self, status: int, body: str):
        reason = self.REASONS.get(status, "Неизвестная ошибка")
        super().__init__(f"HTTP {status}: {reason}. Ответ сервера: {body[:200]}")
        self.status = status


def _retry_after(response: requests.Response) -> int:
    """Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру.

    Значение 0 (или мусор) означало бы «повторяй немедленно» — клиент ушёл бы в
    busy-loop. Значение в десятки минут (так сервер отвечает на исчерпанное часовое
    окно) означало бы «спи почти час» — этого мы тоже не делаем. Возвращаем 0, если
    ждать бессмысленно долго: вызывающий сдастся.
    """
    try:
        seconds = int(response.headers.get("Retry-After", ""))
    except ValueError:
        seconds = 0

    if seconds <= 0:
        return RETRY_DELAY
    return seconds if seconds <= MAX_RETRY_DELAY else 0


def standardize(address: str, region: int | None = None) -> dict:
    """Разбор адресной строки: GET /api/addressstd?address=...

    region — код региона по умолчанию (например, 77), если в строке региона нет.
    Помогает разобрать «улица + дом» без верхнего уровня. Необязателен.

    Разбор выполняется локально, без обращений к внешним источникам, — отсюда
    время ответа в единицы миллисекунд (см. поле `milliseconds` в ответе).
    """
    params: dict[str, object] = {"address": address}
    if region is not None:
        params["region"] = region

    for attempt in range(MAX_RETRIES + 1):
        response = requests.get(
            f"{BASE_URL}/api/addressstd",
            params=params,
            headers={
                "Authorization": f"Bearer {API_KEY}",
                "Accept": "application/json",
            },
            timeout=TIMEOUT,
        )

        # 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
        if response.status_code == 429 and attempt < MAX_RETRIES:
            delay = _retry_after(response)
            if delay == 0:
                raise AtloriumError(429, "лимит ключа по IP исчерпан, повторите позже")
            print(f"  ... лимит запросов, пауза {delay} с", file=sys.stderr)
            time.sleep(delay)
            continue

        if not response.ok:
            raise AtloriumError(response.status_code, response.text)
        return response.json()

    raise AtloriumError(429, "лимит запросов не отпустил после повтора")


# ── Применение данных: чистка адресов в CRM ───────────────────────────────────
# Разобранный адрес сам по себе — просто JSON. Ценность появляется, когда по нему
# принимают РЕШЕНИЕ: можно ли записать этот адрес в базу автоматически — или его
# должен посмотреть человек.
#
# Это и есть смысл коэффициента качества. Автоматизируем то, в чём движок уверен;
# всё сомнительное эскалируем, а не портим базу молча.

ACCEPTED = "ПРИНЯТ"
MANUAL = "НА ПРОВЕРКУ"


@dataclass
class CleanedAddress:
    """Строка адресной базы после чистки — то, что уходит в колонки CRM."""

    source: str          # как ввёл человек
    normalized: str      # нормализованное написание
    quality: int         # коэффициент качества разбора, 0..100
    country: str
    city: str
    street: str
    house: str
    apartment: str
    decision: str        # ПРИНЯТ / НА ПРОВЕРКУ
    reason: str
    elapsed_ms: int = 0


def _component(parsed: dict, level: str) -> dict | None:
    """Первый компонент нужного уровня.

    Уровни (сверху вниз): Country, RegionArea, RegionCity, District, Settlement,
    City, CityDistrict, Locality, Territory, Street, Plot, Building, Apartment, Room.
    """
    for component in parsed.get("components") or []:
        if component.get("level") == level:
            return component
    return None


def _name_of(component: dict | None) -> str:
    """Собственное имя элемента: «Тверская» вместо «улица Тверская».

    `names` — разобранные имена, `text` — готовое к показу нормализованное
    написание. Для колонки в CRM обычно нужно имя, для адресной строки — text.
    """
    if component is None:
        return ""
    names = component.get("names") or []
    return " ".join(names) if names else component.get("text", "")


def _house_of(component: dict | None) -> str:
    """Номер дома вместе с корпусом и строением: «4 к.1 стр.2»."""
    if component is None:
        return ""
    parts = [component.get("number") or ""]
    if component.get("buildNumber"):
        parts.append(f'к.{component["buildNumber"]}')
    if component.get("structNumber"):
        parts.append(f'стр.{component["structNumber"]}')
    return " ".join(part for part in parts if part)


def _clean_one(source: str) -> CleanedAddress:
    parsed = standardize(source)

    quality = int(parsed.get("quality") or 0)
    city = _name_of(_component(parsed, "City"))
    street = _name_of(_component(parsed, "Street"))
    house = _house_of(_component(parsed, "Building"))
    apartment = (_component(parsed, "Apartment") or {}).get("number") or ""

    # ГЛАВНОЕ РЕШЕНИЕ. Ниже порога — адрес НЕ принимается автоматически.
    if quality < QUALITY_THRESHOLD:
        decision = MANUAL
        reason = f"качество {quality} < порога {QUALITY_THRESHOLD}"
    elif not street or not house:
        # Второй фильтр: разбор уверенный, но адрес неполный — без улицы или дома
        # доставить по нему нельзя. Тоже человеку.
        decision = MANUAL
        reason = "не выделены улица или дом"
    else:
        decision = ACCEPTED
        reason = f"качество {quality} >= порога {QUALITY_THRESHOLD}"

    # `message` — сообщение о неточности, влияющей на качество (например, непонятный
    # фрагмент строки). Если оно есть — это готовая подсказка оператору.
    if parsed.get("message"):
        reason = f'{reason}; {parsed["message"]}'

    return CleanedAddress(
        source=source,
        normalized=parsed.get("normalized", ""),
        quality=quality,
        country=parsed.get("countryCode") or "—",
        city=city,
        street=street,
        house=house,
        apartment=apartment,
        decision=decision,
        reason=reason,
        elapsed_ms=int(parsed.get("milliseconds") or 0),
    )


@dataclass
class Summary:
    rows: list[CleanedAddress] = field(default_factory=list)

    @property
    def accepted(self) -> int:
        return sum(1 for row in self.rows if row.decision == ACCEPTED)

    @property
    def manual(self) -> int:
        return sum(1 for row in self.rows if row.decision == MANUAL)


def clean_address_database(addresses: list[str]) -> Summary:
    """Чистка адресной базы: каждый адрес разбирается и получает решение.

    Список намеренно короткий: сервис тарифицируется за запрос, а прогонять базу
    в цикле из примера-демонстрации незачем.
    """
    summary = Summary()
    for address in addresses:
        summary.rows.append(_clean_one(address))
    return summary


def _cut(text: str, width: int) -> str:
    return text if len(text) <= width else text[: width - 1] + "…"


def main() -> int:
    if API_KEY == SANDBOX_KEY:
        print("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n")

    # Первый адрес — из аргумента командной строки, остальные два «грязные»:
    # так люди и вводят адреса в реальные формы.
    primary = sys.argv[1] if len(sys.argv) > 1 else "г Москва, ул. Тверская, д. 7, кв. 12"
    addresses = [
        primary,
        "мск ленинскй проспект д4 кв5",  # сокращение города + опечатка в улице
        "спб невский пр-кт 28",          # сокращения, нет запятых, нет квартиры
    ]

    print(f"Чистка адресной базы. Адресов на входе: {len(addresses)}\n")

    try:
        summary = clean_address_database(addresses)
    except AtloriumError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1

    for index, row in enumerate(summary.rows, start=1):
        print(f"[{index}] «{row.source}»")
        print(f"    Нормализовано: {row.normalized}")
        print(f"    Качество: {row.quality} · Страна: {row.country} · {row.elapsed_ms} мс")
        print(f"    Город: {row.city or '—'} | Улица: {row.street or '—'} | "
              f"Дом: {row.house or '—'} | Кв.: {row.apartment or '—'}")
        print(f"    {row.decision}: {row.reason}\n")

    print(f"{'ИСХОДНАЯ СТРОКА':<34}{'НОРМАЛИЗОВАННЫЙ АДРЕС':<46}{'КАЧ':>4}  РЕШЕНИЕ")
    for row in summary.rows:
        print(f"{_cut(row.source, 33):<34}{_cut(row.normalized, 45):<46}{row.quality:>4}  {row.decision}")

    total = len(summary.rows)
    print("\nИТОГО")
    print(f"  Обработано адресов:    {total}")
    print(f"  Принято автоматически: {summary.accepted}")
    print(f"  На ручную проверку:    {summary.manual}")
    print(f"\nПорог качества: {QUALITY_THRESHOLD}. Молча записать криво разобранный адрес в базу")
    print("хуже, чем отдать его человеку: уверенное автоматизируем, сомнительное эскалируем.")

    if API_KEY == SANDBOX_KEY:
        print("\n[!] ПЕСОЧНИЦА: нормализованный адрес выше НЕ ИМЕЕТ ОТНОШЕНИЯ к введённому —")
        print("    мок генерирует случайный правдоподобный адрес и всегда ставит quality = 100.")
        print("    Поэтому отсева по порогу здесь не видно: он заработает на боевом ключе.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

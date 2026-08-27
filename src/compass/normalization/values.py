import re
import unicodedata
from decimal import Decimal

ITALY_LATITUDE_MIN = Decimal("34.5")
ITALY_LATITUDE_MAX = Decimal("47.5")
ITALY_LONGITUDE_MIN = Decimal("5.5")
ITALY_LONGITUDE_MAX = Decimal("19.0")


def clean_text(value: str | None) -> str | None:
    cleaned = " ".join((value or "").split())
    return cleaned or None


def normalize_text(value: str | None) -> str | None:
    cleaned = clean_text(value)
    if cleaned is None:
        return None
    decomposed = unicodedata.normalize("NFKD", cleaned)
    ascii_like = "".join(char for char in decomposed if not unicodedata.combining(char))
    normalized = " ".join(re.findall(r"[a-z0-9]+", ascii_like.casefold()))
    return normalized or None


def valid_italy_coordinates(latitude: Decimal | None, longitude: Decimal | None) -> bool:
    return bool(
        latitude is not None
        and longitude is not None
        and ITALY_LATITUDE_MIN <= latitude <= ITALY_LATITUDE_MAX
        and ITALY_LONGITUDE_MIN <= longitude <= ITALY_LONGITUDE_MAX
    )

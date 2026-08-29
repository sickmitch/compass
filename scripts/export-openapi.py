#!/usr/bin/env python3
"""Export or verify the checked-in Compass OpenAPI contract."""

import argparse
import json
from pathlib import Path

from compass.api.main import app


def render_openapi() -> str:
    return json.dumps(app.openapi(), indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("docs/openapi.json"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rendered = render_openapi()
    if args.check:
        if not args.output.exists() or args.output.read_text() != rendered:
            parser.error(f"{args.output} is not synchronized with the application")
        return 0
    args.output.write_text(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

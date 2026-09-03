"""The jsonata2py voice of the conformance harness.

Same I/O contract as `_xrun_ref.js` (the reference oracle), `run_js.js` and
`ProbeRunner.java`: a JSON array of ``[{id, expr, input}]`` in, an object of
``{id: {"ok": value} | {"err": "eval:CODE"}}`` out, so all four outputs diff directly.

Usage:  python run_py.py <cases.json> <out.json> [<path-to-jsonata2py-src>]
"""

import json
import sys
import os

DEFAULT_SRC = r"c:\vlad-projects\python\jsonata2py\src"


def main() -> None:
    src = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_SRC
    sys.path.insert(0, src)
    import jsonata2py
    from jsonata2py.errors import JsonataError
    from jsonata2py.runtime.values import MISSING

    with open(sys.argv[1], encoding="utf-8") as handle:
        cases = json.load(handle)

    factory = jsonata2py.JsonataExpressionFactory()
    out = {}
    for case in cases:
        try:
            value = factory.compile(case["expr"]).evaluate(case.get("input"))
            # An absent result serialises as null, because the oracle cannot
            # distinguish undefined from null either.
            out[case["id"]] = {"ok": None if value is MISSING else value}
        except JsonataError as e:
            code = getattr(e, "error_code", None) or type(e).__name__
            out[case["id"]] = {"err": "eval:" + str(code)}
        except Exception as e:  # noqa: BLE001 - a crash is a finding, not a reason to stop
            out[case["id"]] = {"err": "eval:" + type(e).__name__}

    with open(sys.argv[2], "w", encoding="utf-8") as handle:
        json.dump(out, handle, default=_fallback)


def _fallback(value):
    """Function values and anything else unserialisable render as the oracle renders them."""
    return "<fn>"


if __name__ == "__main__":
    main()

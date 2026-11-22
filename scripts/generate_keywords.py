#!/usr/bin/env python3
"""生成 Sherpa-ONNX 唤醒词文件 app/src/main/assets/kws/keywords.txt

用法示例:
    python3 scripts/generate_keywords.py "小智，小智" "你好小智" "小智同学"
    python3 scripts/generate_keywords.py --threshold 0.25 --score 1.5 "小智，小智"
"""

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KEYWORDS_FILE = ROOT / "app/src/main/assets/kws/keywords.txt"
TOKENS_FILE = ROOT / "app/src/main/assets/kws/tokens.txt"
TOKENS_URL = (
    "https://huggingface.co/csukuangfj/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
    "/resolve/main/tokens.txt"
)


def clean_display_name(phrase: str) -> str:
    return re.sub(r"[\s,，。！？!?\u3000]+", "", phrase)


def ensure_tokens_file() -> Path:
    if TOKENS_FILE.exists():
        return TOKENS_FILE
    TOKENS_FILE.parent.mkdir(parents=True, exist_ok=True)
    print(f"未找到本地 tokens.txt，正在下载到 {TOKENS_FILE} ...")
    urllib.request.urlretrieve(TOKENS_URL, TOKENS_FILE)
    return TOKENS_FILE


def convert_with_cli(phrases: list[str], score: float, threshold: float) -> str:
    tokens_path = ensure_tokens_file()
    with tempfile.NamedTemporaryFile("w", suffix=".txt", encoding="utf-8", delete=False) as raw:
        for p in phrases:
            name = clean_display_name(p)
            raw.write(f"{name} :{score} #{threshold} @{name}\n")
        raw_path = Path(raw.name)

    with tempfile.NamedTemporaryFile("r", suffix=".txt", encoding="utf-8", delete=False) as out:
        out_path = Path(out.name)

    try:
        subprocess.run(
            [
                "sherpa-onnx-cli",
                "text2token",
                "--tokens",
                str(tokens_path),
                "--tokens-type",
                "ppinyin",
                str(raw_path),
                str(out_path),
            ],
            check=True,
        )
        return out_path.read_text(encoding="utf-8")
    finally:
        raw_path.unlink(missing_ok=True)
        out_path.unlink(missing_ok=True)


def convert_with_pypinyin(phrases: list[str], score: float, threshold: float) -> str:
    try:
        from pypinyin import Style, pinyin
    except ImportError:
        print(
            "错误：请先安装 sherpa-onnx 或 pypinyin：\n"
            "  pip install sherpa-onnx\n"
            "或：\n"
            "  pip install pypinyin",
            file=sys.stderr,
        )
        sys.exit(1)

    lines = []
    for phrase in phrases:
        name = clean_display_name(phrase)
        initials = pinyin(name, style=Style.INITIALS, strict=False)
        finals = pinyin(name, style=Style.FINALS_TONE, strict=False)
        tokens = []
        for ini, fin in zip(initials, finals):
            if ini[0]:
                tokens.append(ini[0])
            if fin[0]:
                tokens.append(fin[0])
        lines.append(f"{' '.join(tokens)} :{score} #{threshold} @{name}")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="生成小智 Android 唤醒词配置文件")
    parser.add_argument("phrases", nargs="+", help="唤醒词列表，例如: \"小智，小智\" \"你好小智\"")
    parser.add_argument("--score", type=float, default=1.5, help="boosting score (默认 1.5)")
    parser.add_argument("--threshold", type=float, default=0.25, help="trigger threshold (默认 0.25)")
    args = parser.parse_args()

    if shutil.which("sherpa-onnx-cli"):
        output = convert_with_cli(args.phrases, args.score, args.threshold)
    else:
        output = convert_with_pypinyin(args.phrases, args.score, args.threshold)

    KEYWORDS_FILE.parent.mkdir(parents=True, exist_ok=True)
    KEYWORDS_FILE.write_text(output, encoding="utf-8")
    print(f"已写入 {KEYWORDS_FILE}:\n{output}")


if __name__ == "__main__":
    main()

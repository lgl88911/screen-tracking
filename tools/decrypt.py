#!/usr/bin/env python3
"""解开 SCE 容器并释放内部文件。

容器自描述，三种模式无需手工指定：
    0x00 明文      直接解包
    0x01 密码加密  交互输入密码
    0x02 公钥加密  自动读取私钥

用法：
    python3 tools/decrypt.py 2026/11/15.sce -o out/
    python3 tools/decrypt.py 2026/11/15.sce -o out/ -k ~/.config/screentime/private.pem
"""

import argparse
import getpass
import io
import struct
import sys
import zipfile
from pathlib import Path

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
except ImportError:
    sys.exit("缺少依赖：pip install cryptography")

MAGIC = b"SCE1"
MODE_PLAIN, MODE_PASSWORD, MODE_KEY = 0x00, 0x01, 0x02
IV_LEN = 12


def open_container(blob, password=None, priv_key_path=None):
    if len(blob) < 5 or blob[:4] != MAGIC:
        raise ValueError("不是 SCE 容器（magic 不匹配）")

    mode = blob[4]
    p = 5

    if mode == MODE_PLAIN:
        (length,) = struct.unpack(">I", blob[p:p + 4])
        p += 4
        return blob[p:p + length]

    if mode == MODE_PASSWORD:
        if password is None:
            raise ValueError("该文件使用密码加密")
        salt_len = blob[p]
        p += 1
        salt = blob[p:p + salt_len]
        p += salt_len
        (iterations,) = struct.unpack(">I", blob[p:p + 4])
        p += 4
        iv = blob[p:p + IV_LEN]
        p += IV_LEN
        kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32,
                         salt=salt, iterations=iterations)
        dek = kdf.derive(password.encode("utf-8"))
        return AESGCM(dek).decrypt(iv, blob[p:], None)

    if mode == MODE_KEY:
        if priv_key_path is None:
            raise ValueError("该文件使用公钥加密，需要私钥")
        (wrapped_len,) = struct.unpack(">I", blob[p:p + 4])
        p += 4
        wrapped = blob[p:p + wrapped_len]
        p += wrapped_len
        iv = blob[p:p + IV_LEN]
        p += IV_LEN
        priv = serialization.load_pem_private_key(
            Path(priv_key_path).expanduser().read_bytes(), password=None)
        dek = priv.decrypt(
            wrapped,
            padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                         algorithm=hashes.SHA256(), label=None),
        )
        return AESGCM(dek).decrypt(iv, blob[p:], None)

    raise ValueError(f"未知的加密模式: {mode:#04x}")


def describe(blob):
    mode = blob[4] if len(blob) > 4 else -1
    return {MODE_PLAIN: "不加密", MODE_PASSWORD: "密码加密",
            MODE_KEY: "公钥加密"}.get(mode, f"未知({mode})")


def main():
    ap = argparse.ArgumentParser(description="解开 SCE 容器")
    ap.add_argument("file", help=".sce 文件路径")
    ap.add_argument("-o", "--out", default=".", help="输出目录，默认当前目录")
    ap.add_argument("-k", "--key", default="~/.config/screentime/private.pem",
                    help="RSA 私钥路径（仅公钥加密模式需要）")
    ap.add_argument("-p", "--password", help="密码（不填则交互输入）")
    args = ap.parse_args()

    src = Path(args.file)
    blob = src.read_bytes()
    mode_name = describe(blob)
    print(f"{src} — {len(blob)} 字节，{mode_name}")

    password = None
    if len(blob) > 4 and blob[4] == MODE_PASSWORD:
        password = args.password or getpass.getpass("密码：")

    try:
        payload = open_container(blob, password=password, priv_key_path=args.key)
    except Exception as e:
        sys.exit(f"解密失败：{e}")

    out = Path(args.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(io.BytesIO(payload)) as zf:
        names = zf.namelist()
        zf.extractall(out)

    print(f"释放 {len(names)} 个文件到 {out}")
    for n in names:
        print(f"  {n}")


if __name__ == "__main__":
    main()

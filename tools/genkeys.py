#!/usr/bin/env python3
"""生成 SCE 容器使用的 RSA 密钥对（仅加密模式 0x02 需要）。

私钥只应留在 PC，公钥导入手机。私钥丢失后已加密的文件无法恢复。

用法：
    python3 tools/genkeys.py -o ~/.config/screentime/
"""

import argparse
import os
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
except ImportError:
    sys.exit("缺少依赖：pip install cryptography")


def main():
    ap = argparse.ArgumentParser(description="生成 SCE 用的 RSA 密钥对")
    ap.add_argument("-o", "--out", default="~/.config/screentime",
                    help="输出目录，默认 ~/.config/screentime")
    ap.add_argument("--bits", type=int, default=4096, help="密钥长度，默认 4096")
    ap.add_argument("--force", action="store_true", help="覆盖已存在的密钥")
    args = ap.parse_args()

    out = Path(args.out).expanduser()
    priv_path = out / "private.pem"
    pub_path = out / "public.pem"

    if priv_path.exists() and not args.force:
        sys.exit(f"{priv_path} 已存在，加 --force 才会覆盖")

    out.mkdir(parents=True, exist_ok=True)

    print(f"生成 RSA {args.bits} 位密钥对…")
    key = rsa.generate_private_key(public_exponent=65537, key_size=args.bits)

    priv_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    pub_pem = key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    priv_path.write_bytes(priv_pem)
    os.chmod(priv_path, 0o600)
    pub_path.write_bytes(pub_pem)

    print(f"私钥 {priv_path}  (600)")
    print(f"公钥 {pub_path}")
    print()
    print("把 public.pem 的内容导入手机端的「公钥加密」设置。")
    print("私钥请离线备份一份：丢失后已加密的历史数据无法解密。")


if __name__ == "__main__":
    main()

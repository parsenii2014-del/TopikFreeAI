#!/usr/bin/env python3
"""Получает токен MRKT от ТВОЕЙ сессии Telegram и записывает его в файл, который читает сервер (MRKT_TOKEN_FILE).

Запускай у себя (ноутбук или VPS). Логин и код из Telegram вводятся только здесь: ничего не отправляй никому,
файл сессии (*.session) тоже секретный. Для автоматики лучше завести отдельный аккаунт Telegram.
Метод описан в неофициальной документации MRKT (github.com/boostNT/MRKT-API): initData мини-приложения -> POST /auth -> токен.

  pip install pyrogram tgcrypto requests
  python mrkt_token.py --api-id 123456 --api-hash abcdef... --out ../server/data/mrkt.token

Токен живёт больше суток: запускай по cron раз в 12 часов (после первого входа сессия сохранена).
api_id и api_hash берутся на https://my.telegram.org (раздел API development tools).
"""
import argparse
import asyncio
import os
import sys
from urllib.parse import unquote

import requests
from pyrogram import Client
from pyrogram.raw.functions.messages import RequestAppWebView
from pyrogram.raw.types import InputBotAppShortName, InputUser

API = "https://api.tgmrkt.io/api/v1"


async def get_init_data(api_id: int, api_hash: str, session: str) -> str:
    client = Client(session, api_id=api_id, api_hash=api_hash)
    async with client:
        bot_entity = await client.get_users("mrkt")
        peer = await client.resolve_peer("mrkt")
        bot = InputUser(user_id=bot_entity.id, access_hash=bot_entity.raw.access_hash)
        app = InputBotAppShortName(bot_id=bot, short_name="app")
        web_view = await client.invoke(RequestAppWebView(peer=peer, app=app, platform="android"))
    return unquote(web_view.url.split("tgWebAppData=", 1)[1].split("&tgWebAppVersion", 1)[0])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api-id", type=int, required=True)
    ap.add_argument("--api-hash", required=True)
    ap.add_argument("--session", default="mrkt_session", help="имя файла сессии Pyrogram")
    ap.add_argument("--out", default="mrkt.token", help="куда записать токен")
    ap.add_argument("--proxy", default=None, help="http/socks5 прокси вида socks5://host:port, если Telegram недоступен напрямую")
    args = ap.parse_args()

    init_data = asyncio.run(get_init_data(args.api_id, args.api_hash, args.session))
    proxies = {"https": args.proxy, "http": args.proxy} if args.proxy else None
    r = requests.post(f"{API}/auth", json={"data": init_data}, headers={"Referer": "https://cdn.tgmrkt.io/"}, proxies=proxies, timeout=30)
    r.raise_for_status()
    token = (r.json() or {}).get("token")
    if not token:
        sys.exit("MRKT не вернул токен")
    tmp = args.out + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token)
    os.replace(tmp, args.out)
    print(f"Токен записан в {args.out}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate a deterministic, original 1500-chapter Chinese stress-test novel."""
from pathlib import Path
import random
import sys

ROOT = Path(__file__).resolve().parent
CHAPTERS = int(sys.argv[1]) if len(sys.argv) > 1 else 1500
OUTPUT = ROOT / (sys.argv[2] if len(sys.argv) > 2 else "千帆纪事_1500章.txt")

places = [
    "雾港", "青石渡", "北岬灯塔", "旧钟楼", "盐风镇", "长桥", "白鸦坡",
    "云门", "沉星湖", "潮汐书院", "黑礁湾", "松烟谷", "临海驿", "南陵城",
    "断帆崖", "月牙湾", "听雨巷", "风眠岛", "古渠口", "赤砂岸",
]
events = [
    "来信", "夜航", "灯火", "旧图", "远客", "潮声", "密约", "失舟",
    "归途", "守夜", "风声", "残页", "渡口", "雪讯", "晨钟", "暗礁",
    "重逢", "远山", "星图", "一页新章",
]
characters = ["沈舟", "林砚", "阿遥", "老周", "陆青", "闻雪", "顾临", "许七", "白鸥"]
objects = [
    "牛皮封面的航海笔记", "缺角的铜制罗盘", "一束没有署名的信", "刻着潮汐刻度的木尺",
    "半张被海水泡皱的海图", "一盏不会熄灭的小灯", "一串磨得发亮的钥匙",
    "写满日期的旧船票", "一只装着细沙的玻璃瓶", "卷在竹筒里的星图",
]
weathers = [
    "细雨沿着屋檐落下", "薄雾贴着水面缓慢移动", "风从北边吹来", "天色在云层后渐渐放亮",
    "远处的雷声越过山脊", "海面平静得像一张展开的纸", "晚霞落在礁石上", "雪粒轻轻敲着窗棂",
]
actions = [
    "把所有线索重新排在桌面", "沿着潮线寻找被冲上岸的木头", "在灯下比对两张不同的地图",
    "去码头询问最后一班渡船", "翻看旧书里折叠起来的页角", "把听到的每一句话记进本子",
    "沿着石阶走向镇子最高处", "在钟声结束前穿过长桥", "借着晨光辨认罗盘上的刻痕",
    "把钥匙逐一试过书院的旧门",
]
reflections = [
    "答案也许并不在远方，而藏在被忽略的细节里。",
    "每一次选择都会改变下一段航程，但方向仍可以由自己决定。",
    "人们以为潮水带走了一切，其实它也把新的东西送到了岸边。",
    "地图记录道路，真正决定路通向何处的，是走路的人。",
    "有些约定不需要大声说出，只要在关键时刻如约出现。",
    "夜色再长，也总会留下一点足够看清脚下的光。",
    "故事从来不会因为合上书页而停止，它只是在等待下一次被翻开。",
    "远行并不意味着离开，而是带着旧日的记忆去寻找新的回答。",
]

rng = random.Random(20261024)


def paragraph(chapter: int, part: int) -> str:
    who = characters[(chapter + part) % len(characters)]
    place = places[(chapter * 3 + part) % len(places)]
    item = objects[(chapter + part * 2) % len(objects)]
    weather = weathers[(chapter * 2 + part) % len(weathers)]
    action = actions[(chapter * 5 + part) % len(actions)]
    thought = reflections[(chapter + part * 3) % len(reflections)]
    return (
        f"{weather}。{who}在{place}停下脚步，手里仍拿着那件{item}。"
        f"这一天与昨天并没有太大不同，可海风转向以后，许多事情都悄悄有了新的顺序。"
        f"{who}决定{action}，又在纸上补下几行只有自己看得懂的标记。"
        f"途中没有人催促，只有远处传来的水声提醒着时间仍在向前。"
        f"{thought}"
    )


with OUTPUT.open("w", encoding="utf-8", newline="\n") as book:
    for chapter in range(1, CHAPTERS + 1):
        place = places[(chapter * 7) % len(places)]
        event = events[(chapter * 11) % len(events)]
        title = f"第{chapter}章 {place}{event}"
        book.write(title + "\n\n")
        if chapter == 1:
            book.write("作者：墨读测试\n\n")
            book.write("这是一部用于阅读器压力测试的原创长篇小说。故事、人物和地点均为虚构。\n\n")
        book.write(
            f"潮历第{chapter}日，{place}的清晨比往常更早一些。"
            f"沈舟从短暂的睡眠中醒来，窗外传来船缆轻碰木桩的声音。"
            f"昨日留下的问题还没有答案，而新的渡船已经靠岸。\n\n"
        )
        for part in range(10):
            book.write(paragraph(chapter, part) + "\n\n")
        book.write(
            f"入夜前，沈舟把{objects[(chapter * 4) % len(objects)]}收进衣袋。"
            f"他在本章的末尾写下日期，又留出一行空白，等下一次潮汐带来新的消息。\n\n"
        )

print(OUTPUT)
print(f"chapters={CHAPTERS}, bytes={OUTPUT.stat().st_size}")

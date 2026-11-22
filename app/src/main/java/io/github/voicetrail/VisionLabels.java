package io.github.voicetrail;

import java.util.Locale;

/** Spoken names for common scene categories. Keep an unknown model label verbatim. */
final class VisionLabels {
    private VisionLabels() {}

    static String chinese(String label) {
        switch (label.toLowerCase(Locale.ROOT)) {
            case "fruit": return "水果";
            case "vegetable": return "蔬菜";
            case "food": return "食物";
            case "dish": case "cuisine": return "菜品";
            case "meal": return "一餐食物";
            case "flower": return "花";
            case "plant": return "植物";
            case "tree": return "树";
            case "houseplant": return "盆栽";
            case "grass": return "草";
            case "garden": return "花园";
            case "sky": return "天空";
            case "cloud": return "云";
            case "beach": return "沙滩";
            case "lake": return "湖";
            case "mountain": return "山";
            case "water": return "水";
            case "animal": return "动物";
            case "bird": return "鸟";
            case "cat": return "猫";
            case "dog": return "狗";
            case "pet": return "宠物";
            case "person": case "human": return "人";
            case "crowd": return "人群";
            case "smile": return "笑容";
            case "building": return "建筑";
            case "house": return "房屋";
            case "room": return "房间";
            case "kitchen": return "厨房";
            case "bathroom": return "浴室";
            case "bedroom": return "卧室";
            case "chair": return "椅子";
            case "table": return "桌子";
            case "furniture": return "家具";
            case "shelf": return "架子";
            case "floor": return "地板";
            case "window": return "窗户";
            case "door": return "门";
            case "bottle": return "瓶子";
            case "cup": return "杯子";
            case "glass": return "玻璃";
            case "bowl": return "碗";
            case "plate": return "盘子";
            case "bag": return "包";
            case "box": return "盒子";
            case "paper": return "纸";
            case "document": return "文档";
            case "receipt": return "收据";
            case "book": return "书";
            case "poster": return "海报";
            case "sign": return "标牌";
            case "clothing": return "衣物";
            case "shirt": return "衬衫";
            case "shoe": return "鞋";
            case "toy": return "玩具";
            case "car": return "汽车";
            case "bus": return "公共汽车";
            case "bicycle": return "自行车";
            case "vehicle": return "车辆";
            case "road": return "道路";
            case "street": return "街道";
            case "computer": return "电脑";
            case "laptop": return "笔记本电脑";
            case "mobile phone": return "手机";
            case "keyboard": return "键盘";
            case "electronic device": return "电子设备";
            case "piano": return "钢琴";
            case "guitar": return "吉他";
            case "metal": return "金属";
            case "wood": return "木材";
            case "plastic": return "塑料";
            default: return "英文类别 " + label;
        }
    }
}

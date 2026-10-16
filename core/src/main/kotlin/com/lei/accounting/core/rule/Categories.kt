package com.lei.accounting.core.rule

import com.lei.accounting.core.model.BillType

/**
 * 分类表 + 本地关键词规则。
 * 本地规则先给出一个初始分类，联网后再交给 DeepSeek 精修。
 */
object Categories {
    const val OTHER_EXPENSE = "其他"
    const val OTHER_INCOME = "其他收入"

    val expense = listOf(
        "餐饮", "交通", "购物", "日用", "娱乐", "住房", "通讯", "医疗", "教育", "人情", "转账", OTHER_EXPENSE,
    )
    val income = listOf(
        "工资", "红包", "退款", "转账", "理财", OTHER_INCOME,
    )

    fun of(type: BillType): List<String> = if (type == BillType.EXPENSE) expense else income

    fun default(type: BillType): String = if (type == BillType.EXPENSE) OTHER_EXPENSE else OTHER_INCOME

    /** 校验分类名，非法时回退为「其他」 */
    fun normalize(type: BillType, name: String?): String {
        val n = name?.trim().orEmpty()
        return if (n in of(type)) n else default(type)
    }

    // 顺序有意义：越具体的越靠前
    private val expenseKeywords: List<Pair<String, Regex>> = listOf(
        "交通" to Regex("滴滴|高德打车|曹操|T3出行|花小猪|地铁|公交|乘车码|出行|打车|加油|中石化|中石油|壳牌|停车|12306|铁路|航空|机票|哈啰|青桔|美团单车|ETC|高速"),
        "通讯" to Regex("话费|流量|中国移动|中国联通|中国电信|宽带"),
        "住房" to Regex("房租|租金|物业|水费|电费|燃气|国家电网|自如|贝壳"),
        "医疗" to Regex("医院|药房|药店|诊所|大药房|体检|口腔"),
        "教育" to Regex("课程|培训|学费|书店|教育|网课|知识星球"),
        "娱乐" to Regex("电影|影城|影院|游戏|腾讯视频|爱奇艺|优酷|芒果TV|哔哩哔哩|bilibili|网易云|QQ音乐|KTV|Steam|健身|演出|门票|景区"),
        "餐饮" to Regex("美团外卖|饿了么|肯德基|KFC|麦当劳|星巴克|瑞幸|库迪|奶茶|喜茶|蜜雪|茶百道|古茗|霸王茶姬|沪上阿姨|必胜客|汉堡|塔斯汀|华莱士|烧烤|火锅|小吃|面包|烘焙|餐|饭|面馆|粉|食堂|咖啡|外卖|美食|饮品|酒楼|饺子|包子"),
        "日用" to Regex("超市|便利店|7-ELEVEN|7-11|全家|罗森|美宜佳|便利蜂|盒马|永辉|物美|大润发|沃尔玛|山姆|朴朴|叮咚|多多买菜|美团优选|菜市场|生鲜|水果"),
        "购物" to Regex("淘宝|天猫|京东|拼多多|唯品会|抖音|得物|闲鱼|商城|旗舰店|专卖店|优衣库|名创优品|苹果|Apple"),
        "人情" to Regex("红包|亲属卡"),
        "转账" to Regex("转账|转给"),
    )

    private val incomeKeywords: List<Pair<String, Regex>> = listOf(
        "退款" to Regex("退款|退回|退还"),
        "工资" to Regex("工资|薪|代发"),
        "红包" to Regex("红包"),
        "理财" to Regex("收益|利息|分红|余额宝|理财|基金"),
        "转账" to Regex("转账|收款|向你付款"),
    )

    /** 根据商户名/原文用关键词猜一个分类 */
    fun guess(type: BillType, vararg texts: String): String {
        val haystack = texts.joinToString(" ")
        val table = if (type == BillType.EXPENSE) expenseKeywords else incomeKeywords
        return table.firstOrNull { (_, re) -> re.containsMatchIn(haystack) }?.first ?: default(type)
    }
}

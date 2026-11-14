package io.github.zenthxsin.rustwright.ui.kit

/**
 * 原型（App.dc.html）里的图标路径，24×24 视框。名字与原型 IC 表一致；内联图标按用途命名。
 * 圆 / 矩形已换写成路径。
 */
object PI {
    // ---- 原型 IC 表 ----
    const val chat = "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"
    const val box = "M21 8 12 3 3 8v8l9 5 9-5zM3 8l9 5 9-5M12 13v8"
    const val layers = "m12 3 9 5-9 5-9-5zM3 13l9 5 9-5"
    const val grid = "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 17h6M17 14v6"
    const val term = "M6 4h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3zM7 9l3 3-3 3M13 15h4"
    const val img = "M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM9 7a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM21 15l-5-5L5 21"
    const val map = "M9 4 3 6v14l6-2 6 2 6-2V4l-6 2zM9 4v14M15 6v14"
    const val code = "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6M10 13l-2 2 2 2M14 13l2 2-2 2"
    const val eye = "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"
    const val pen = "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5M14 3v6h6M14 3l6 6v3M13 21l1-4 5-5 3 3-5 5z"
    const val shield = "M12 3 5 6v6c0 4 3 7.5 7 9 4-1.5 7-5 7-9V6zM9 12l2 2 4-4"
    const val dots = "M12 4.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1zM12 11.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1zM12 18.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z"
    const val clock = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2"
    const val search = "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4"
    const val info = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7.5v.01"
    const val down = "M12 3v12M7 10l5 5 5-5M5 21h14"
    /** 保存（Lucide save）。 */
    const val save = "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7M7 3v4a1 1 0 0 0 1 1h7"
    const val up = "M12 15V3M7 8l5-5 5 5M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5"
    const val edit = "M4 20h4L19 9l-4-4L4 16z"
    const val archive = "M3 4h18v5H3zM5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4"
    const val camera = "M4 8h3l2-3h6l2 3h3v11H4zM12 10a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z"
    const val file = "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6"
    const val user = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM6.5 18a6 6 0 0 1 11 0"
    const val wallet = "M4 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM4 7l11-3v3M16 13.5h.01"
    const val gauge = "M4 18a9 9 0 1 1 16 0M12 14l4-5"
    const val sliders = "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M16 4v4M10 10v4M18 16v4"
    const val book = "M6 3h12v18l-6-4-6 4z"
    const val plug = "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4"
    const val moon = "M20 14.5A8 8 0 1 1 9.5 4 6.5 6.5 0 0 0 20 14.5z"
    const val type = "M4 18 9 6l5 12M6 14h6M15 18v-6a2.5 2.5 0 0 1 5 0v6M15 15h5"

    // ---- 内联图标 ----
    const val chevronRight = "m9 6 6 6-6 6"
    const val chevronDown = "m6 9 6 6 6-6"
    const val chevronUp = "m18 15-6-6-6 6"
    const val check = "M20 6 9 17l-5-5"
    const val plus = "M12 5v14M5 12h14"
    /** Lucide clipboard */
    const val paste = "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"
    /** 全选：虚线方框里一个实心框（Material select_all 的线稿版） */
    const val selectAll = "M5 3a2 2 0 0 0-2 2M19 3a2 2 0 0 1 2 2M21 19a2 2 0 0 1-2 2M5 21a2 2 0 0 1-2-2M9 3h1M14 3h1M9 21h1M14 21h1M3 9v1M3 14v1M21 9v1M21 14v1M8 8h8v8H8z"
    const val copy = "M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM5 15V5a2 2 0 0 1 2-2h10"
    const val arrowUp = "M12 19V5M5 12l7-7 7 7"
    const val arrowDown = "M12 5v14M19 12l-7 7-7-7"
    const val back = "M19 12H5M11 6l-6 6 6 6"
    const val close = "M18 6 6 18M6 6l12 12"
    const val menu = "M4 7h16M4 12h11M4 17h7"
    const val steer = "M9 10 4 15l5 5M20 4v7a4 4 0 0 1-4 4H4"
    const val retry = "M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5"
    const val play = "M7 4.5v15l12.5-7.5z"
    const val stop = "M7 7h10v10H7z"
    const val refresh = "M21 12a9 9 0 1 1-2.6-6.4L21 8M21 3v5h-5"
    const val mic = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3"
    const val keyboard = "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"
    const val warn = "M12 3 2 20h20zM12 10v4M12 17v.01"
    const val fileUp = "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6M12 18v-6M9 15l3-3 3 3"

    // ---- 工作模式 ----
    const val bolt = "M13 2 4 14h7l-1 8 9-12h-7z"
    const val codeBrackets = "m8 7-5 5 5 5M16 7l5 5-5 5M14 4l-4 16"

    const val plan = "M8 21h10a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2H8M8 21a3 3 0 0 1-3-3v-1h11v1a3 3 0 0 0 3 3M8 3a3 3 0 0 0-3 3v11M9 8h6M9 12h6"

    // ---- 「更多」里的工具 ----
    const val palette = "M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2s-1-2 0-3 2 0 3 0a4 4 0 0 0 4-4c0-5-4-9-9-9zM7.5 11h.01M10 7h.01M15 7.5h.01"
    const val server = "M4 5h16v6H4zM4 13h16v6H4zM8 8h.01M8 16h.01"
    const val waves = "M4 17c3-6 5-6 8 0s5 6 8 0M4 9c3-4 5-4 8 0s5 4 8 0"
    const val tiles = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
    const val pixel = "M4 4h5v5H4zM9 9h6v6H9zM15 15h5v5h-5zM15 4h5v5h-5zM4 15h5v5H4z"
    const val crop = "M6 2v14a2 2 0 0 0 2 2h14M18 22V8a2 2 0 0 0-2-2H2"
    // 地图画布（lucide undo-2 / redo-2 / minus / scan / shuffle / gem / trees / eraser）
    const val undo = "M9 14 4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11"
    const val redo = "m15 14 5-5-5-5M20 9H9.5a5.5 5.5 0 0 0 0 11H13"
    const val minus = "M5 12h14"
    const val fit = "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2"
    const val shuffle = "m18 14 4 4-4 4M18 2l4 4-4 4M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22M2 6h1.9c1.5 0 2.9.9 3.6 2.2M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8"
    const val gem = "M6 3h12l4 6-10 13L2 9zM11 3 8 9l4 13 4-13-3-6M2 9h20"
    const val trees = "M10 10v.2A3 3 0 0 1 8.9 16H5a3 3 0 0 1-1-5.8V10a3 3 0 0 1 6 0zM7 16v6M13 19v3M12 19h8.3a1 1 0 0 0 .7-1.7L18 14h.3a1 1 0 0 0 .7-1.7L16 9h.2a1 1 0 0 0 .8-1.7L13 3l-1.4 1.5"
    const val eraser = "m7 21-4.3-4.3a1 1 0 0 1 0-1.4l10-10a1 1 0 0 1 1.4 0l5.6 5.6a1 1 0 0 1 0 1.4L11 21M22 21H7M5 11l9 9"
    const val folder = "M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
    const val scissors = "M6 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM6 15a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM20 4 8.12 15.88M14.47 14.48 20 20M8.12 8.12 12 12"
    const val trash = "M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
    const val indentMore = "M21 12H11M21 18H11M21 6H11M3 8l4 4-4 4"
    const val indentLess = "M21 12H11M21 18H11M21 6H11M7 8l-4 4 4 4"
    const val hash = "M4 9h16M4 15h16M10 3 8 21M16 3l-2 18"
    const val caseUpper = "M3 18 7 6l4 12M4.3 14h5.4M15 6h3.5a3 3 0 0 1 0 6H15zM15 12h4a3 3 0 0 1 0 6h-4z"
    const val caseLower = "M7 11a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM10 11v6M14 6v11M17 11a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"
    const val copyPlus = "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2zM4 16V4a2 2 0 0 1 2-2h10M15 12v6M12 15h6"
    const val gotoLine = "M3 6h8M3 12h12M3 18h8M15 8l4 4-4 4"
    const val zoom = "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4M8 11h6M11 8v6"
    const val crosshair = "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM22 12h-4M6 12H2M12 6V2M12 22v-4"
    const val wrap = "M3 6h18M3 12h15a3 3 0 1 1 0 6h-4M16 16l-2 2 2 2M3 18h7"
}

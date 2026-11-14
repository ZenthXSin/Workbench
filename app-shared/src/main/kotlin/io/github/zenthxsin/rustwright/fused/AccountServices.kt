package io.github.zenthxsin.rustwright.fused


/** AccountServices: a separately implementable workbench capability. */
interface AccountServices {
    val accountEmail: String
    fun balance(): String?
    fun benefitsState(): BenefitsState = BenefitsState.SignedOut
    fun refreshBenefits() {}
    fun settings(): List<List<SettingRow>>
    /** 设置页各组的小标题（与 [settings] 一一对应，可少于组数）。 */
    fun settingTitles(): List<String> = emptyList()
    /** 设置页顶部账号卡点开的页面。 */
    fun openProfile() {}
    /** 按 NewAPI 角色显示 / 隐藏设置里的「开发者工具」（管理员 role ≥ 10 才显示）。 */
    fun showDevTools(role: Int) {}
}

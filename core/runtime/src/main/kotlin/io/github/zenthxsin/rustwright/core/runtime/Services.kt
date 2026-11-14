package io.github.zenthxsin.rustwright.core.runtime

import kotlin.reflect.KClass

/**
 * 极简服务容器（组合根）：按类型注册单例或惰性工厂，按类型取用。
 * 不做反射扫描，依赖关系由各模块在 [RwModule.register] 中显式声明。
 */
class Services {
    private val instances = HashMap<KClass<*>, Any>()
    private val factories = HashMap<KClass<*>, () -> Any>()

    fun <T : Any> provide(type: KClass<T>, instance: T) {
        check(type !in instances && type !in factories) { "服务重复注册：${type.qualifiedName}" }
        instances[type] = instance
    }

    fun <T : Any> provideLazy(type: KClass<T>, factory: () -> T) {
        check(type !in instances && type !in factories) { "服务重复注册：${type.qualifiedName}" }
        factories[type] = factory
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrNull(type: KClass<T>): T? = synchronized(this) {
        instances[type] as T? ?: factories.remove(type)?.let { f -> (f() as T).also { instances[type] = it } }
    }

    fun <T : Any> get(type: KClass<T>): T = getOrNull(type) ?: error("未注册服务：${type.qualifiedName}")

    operator fun contains(type: KClass<*>): Boolean = type in instances || type in factories

    inline fun <reified T : Any> provide(instance: T) = provide(T::class, instance)
    inline fun <reified T : Any> provideLazy(noinline factory: () -> T) = provideLazy(T::class, factory)
    inline fun <reified T : Any> get(): T = get(T::class)
    inline fun <reified T : Any> getOrNull(): T? = getOrNull(T::class)
}

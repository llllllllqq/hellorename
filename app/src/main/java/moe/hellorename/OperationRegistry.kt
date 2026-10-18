package moe.hellorename

/**
 * 操作代际：复制、prepare、MediaStore 保存、SAF 写入都捕获自己的 token。
 *
 * 只要 token 不是当前代际，IO 回到主线程后就必须丢弃结果：不更新 UI、不 finish、
 * 不写交付标记。这样“保存旧文件时收到新分享”不会把新界面关掉。
 *
 * 纯逻辑、线程安全，方便单测。
 */
class OperationRegistry {

    private var generation = 0L

    /** 开始新的一代（新分享、重置、恢复），旧 token 立即失效 */
    @Synchronized
    fun begin(): Long {
        generation += 1
        return generation
    }

    /** 让当前代际失效，返回新的 token */
    @Synchronized
    fun invalidate(): Long = begin()

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation

    @Synchronized
    fun resetForTest() {
        generation = 0L
    }
}

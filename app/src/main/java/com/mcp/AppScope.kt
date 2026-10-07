package com.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级后台作用域：承载「不该随页面销毁而中断」的长任务。
 *
 * 目前用于 PRoot Debian 的解压安装。它是分钟级重活（拷贝 rootfs 压缩包 + XZ 解压 +
 * 落盘上万个文件 + 健康检查），若挂在 Activity/Fragment 的 lifecycleScope 上，
 * 用户一点「下一步」离开页面协程就被取消；而 [ProotEnvironment.ensureInitialized] 的
 * finally 会删除未完成的 staging 目录——等于白干一场，下次还得从头再来。
 *
 * 生命周期与进程一致，进程结束即释放，无需手动取消。
 */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

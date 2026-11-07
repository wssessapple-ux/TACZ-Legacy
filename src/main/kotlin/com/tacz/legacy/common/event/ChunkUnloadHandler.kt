package com.tacz.legacy.common.event

import com.tacz.legacy.common.entity.EntityDrone
import com.tacz.legacy.common.entity.EntityKineticBullet
import net.minecraftforge.event.world.ChunkEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

/**
 * 区块卸载时清理该区块里的所有子弹。
 *
 * 根因：子弹在未加载区块里不 tick，lifespan 不递减，永远堆积。
 * 解决方案：区块卸载时，直接标记该区块里的所有子弹为死亡，防止堆积。
 *
 * 参考案例：B 站视频《因为一只凋灵，我们的服务器几乎崩溃》——
 * 凋灵之首从强加载区块飞入弱加载区块后停止计算运动但仍计实体数据，久了延迟积累崩服。
 */
internal object ChunkUnloadHandler {
    @SubscribeEvent
    fun onChunkUnload(event: ChunkEvent.Unload) {
        // 只在服务端清理（客户端区块卸载不需要清理子弹）
        val world = event.world
        if (world.isRemote) return

        val chunk = event.chunk
        // 遍历该区块里的所有实体，找到子弹/无人机，标记为死亡
        for (entity in chunk.entityLists) {
            if (entity is EntityKineticBullet || entity is EntityDrone) {
                entity.setDead()
            }
        }
    }
}

package top.yurikale.landFight.ui

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapPalette
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.team.TeamColor

class MapManager(private val plugin: LandFight) {
    private var gameMapView: MapView? = null

    fun initGameMap() {
        val world = Bukkit.getWorld(plugin.worldManager.gameWorldName) ?: return
        gameMapView = Bukkit.createMap(world)
        gameMapView?.let { mapView ->
            mapView.scale = MapView.Scale.FARTHEST
            mapView.isUnlimitedTracking = true
            mapView.centerX = 0
            mapView.centerZ = 0
//            mapView.isTrackingPosition = true
//            mapView.isUnlimitedTracking = true
            mapView.renderers.clear()
            mapView.addRenderer(BaseMapRenderer(plugin))
        }
    }

    fun giveMapToPlayer(player: Player) {
        val mapView = gameMapView ?: return
        val hasMap = player.inventory.any { item ->
            item != null && item.itemMeta is MapMeta && (item.itemMeta as MapMeta).mapView == mapView
        }
        if (hasMap) return

        val mapItem = ItemStack(org.bukkit.Material.FILLED_MAP)
        val meta = mapItem.itemMeta as MapMeta
        meta.mapView = mapView
        meta.setDisplayName("§6战场据点地图")
        meta.lore = listOf("§7实时显示所有据点与大本营")
        mapItem.itemMeta = meta
        player.inventory.addItem(mapItem)
    }

    fun clearMap() {
        gameMapView?.renderers?.clear()
        gameMapView = null
    }
}

class BaseMapRenderer(private val plugin: LandFight) : MapRenderer(false) {
    private val worldHalfSize = 750
    private val canvasSize = 128

    override fun render(mapView: MapView, canvas: MapCanvas, player: Player) {
        // 填充草地背景
        val bgColor = MapPalette.DARK_GREEN
        for (x in 0 until canvasSize) {
            for (z in 0 until canvasSize) {
                canvas.setPixel(x, z, bgColor)
            }
        }

        // 绘制所有据点
        val bases = plugin.structurePlacer.activeBases

        // 直接遍历 values，拿到每一个 Base 对象
        bases.values.forEach { base ->
            val loc = base.location
            val pixelX = worldToPixel(loc.blockX)
            val pixelZ = worldToPixel(loc.blockZ)

            // 直接通过 base.ownerTeam 枚举进行判断
            val dotColor = when (base.ownerTeam) {
                TeamColor.RED -> MapPalette.RED
                TeamColor.BLUE -> MapPalette.BLUE
                else -> MapPalette.DARK_GRAY
            }
            drawDot(canvas, pixelX, pixelZ, dotColor, radius = 1)
        }

        // 红队大本营
        val redCapital = plugin.teamManager.teamsCapitals[TeamColor.RED]
        redCapital?.let { loc ->
            val px = worldToPixel(loc.blockX)
            val pz = worldToPixel(loc.blockZ)
            drawDot(canvas, px, pz, MapPalette.RED, radius = 3)
        }

        // 蓝队大本营
        val blueCapital = plugin.teamManager.teamsCapitals[TeamColor.BLUE]
        blueCapital?.let { loc ->
            val px = worldToPixel(loc.blockX)
            val pz = worldToPixel(loc.blockZ)
            drawDot(canvas, px, pz, MapPalette.BLUE, radius = 3)
        }

        // ========== 新增：绘制玩家自身位置白点 ==========
        val playerLoc = player.location
        val playerPx = worldToPixel(playerLoc.blockX)
        val playerPz = worldToPixel(playerLoc.blockZ)
        // 白色、半径2，醒目区分所有据点
        drawDot(canvas, playerPx, playerPz, MapPalette.WHITE, radius = 2)
    }

    private fun worldToPixel(worldCoord: Int): Int {
        val normalized = (worldCoord + worldHalfSize).toFloat() / (worldHalfSize * 2)
        return (normalized * canvasSize).toInt().coerceIn(0, canvasSize - 1)
    }

    private fun drawDot(ctx: MapCanvas, cx: Int, cz: Int, color: Byte, radius: Int) {
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (dx * dx + dz * dz <= radius * radius) {
                    val x = cx + dx
                    val z = cz + dz
                    if (x in 0 until canvasSize && z in 0 until canvasSize) {
                        ctx.setPixel(x, z, color)
                    }
                }
            }
        }
    }
}
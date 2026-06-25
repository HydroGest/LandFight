package top.yurikale.landFight.ui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapPalette
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapCursor
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.team.TeamColor

class MapManager(private val plugin: LandFight) {
    private var gameMapView: MapView? = null

    fun initGameMap() {
        val world = Bukkit.getWorld(plugin.worldManager.gameWorldName) ?: return
        gameMapView = Bukkit.createMap(world)
        gameMapView?.let { mapView ->
            mapView.scale = MapView.Scale.FARTHEST
            mapView.isTrackingPosition = false
            mapView.isUnlimitedTracking = false
            mapView.centerX = 0
            mapView.centerZ = 0
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
        meta.setDisplayName("§6战场据点与交通网地图")
        meta.lore = listOf("§7实时显示据点、交通线与您的朝向")
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
        // 1. 填充草地背景
        val bgColor = MapPalette.DARK_GREEN
        for (x in 0 until canvasSize) {
            for (z in 0 until canvasSize) {
                canvas.setPixel(x, z, bgColor)
            }
        }

        val bases = plugin.structurePlacer.activeBases
        val graph = plugin.structurePlacer.networkGraph

        // 2. 绘制交通线
        val drawnEdges = mutableSetOf<String>()
        bases.values.forEach { base ->
            val neighbors = graph.getNeighbors(base.id)
            neighbors.forEach { neighborId ->
                val targetBase = bases[neighborId] ?: return@forEach

                val edgeId = if (base.id < neighborId) "${base.id}-$neighborId" else "$neighborId-${base.id}"

                if (drawnEdges.add(edgeId)) {
                    val x1 = worldToPixel(base.location.blockX)
                    val z1 = worldToPixel(base.location.blockZ)
                    val x2 = worldToPixel(targetBase.location.blockX)
                    val z2 = worldToPixel(targetBase.location.blockZ)

                    val lineColor = when (base.ownerTeam) {
                        TeamColor.RED -> MapPalette.RED
                        TeamColor.BLUE -> MapPalette.BLUE
                        else -> MapPalette.DARK_GRAY
                    }
                    drawLine(canvas, x1, z1, x2, z2, lineColor)
                }
            }
        }

        // 3. 绘制所有据点圆点
        bases.values.forEach { base ->
            val loc = base.location
            val pixelX = worldToPixel(loc.blockX)
            val pixelZ = worldToPixel(loc.blockZ)

            val dotColor = when (base.ownerTeam) {
                TeamColor.RED -> MapPalette.RED
                TeamColor.BLUE -> MapPalette.BLUE
                else -> MapPalette.DARK_GRAY
            }
            drawDot(canvas, pixelX, pixelZ, dotColor, radius = 1)
        }

        // 4. 绘制红队大本营
        plugin.teamManager.teamsCapitals[TeamColor.RED]?.let { loc ->
            drawDot(canvas, worldToPixel(loc.blockX), worldToPixel(loc.blockZ), MapPalette.RED, radius = 3)
        }

        // 5. 绘制蓝队大本营
        plugin.teamManager.teamsCapitals[TeamColor.BLUE]?.let { loc ->
            drawDot(canvas, worldToPixel(loc.blockX), worldToPixel(loc.blockZ), MapPalette.BLUE, radius = 3)
        }

        val cursors = canvas.cursors
        // 清除上一帧的历史游标（防止拖影）
        while (cursors.size() > 0) {
            cursors.removeCursor(cursors.getCursor(0))
        }

        val playerLoc = player.location
        val playerPx = worldToPixel(playerLoc.blockX)
        val playerPz = worldToPixel(playerLoc.blockZ)

        val cursorX = (playerPx * 2 - 128).coerceIn(-128, 127).toByte()
        val cursorZ = (playerPz * 2 - 128).coerceIn(-128, 127).toByte()

        val normYaw = (playerLoc.yaw % 360 + 360) % 360 // 将 Yaw 限制在 0~360 之间
        val cursorDirection = Math.round(normYaw / 22.5f).toInt() % 16

        cursors.addCursor(
            MapCursor(cursorX, cursorZ, cursorDirection.toByte(), MapCursor.Type.PLAYER, true)
        )
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

    private fun drawLine(canvas: MapCanvas, x1: Int, z1: Int, x2: Int, z2: Int, color: Byte) {
        var x = x1
        var z = z1
        val dx = kotlin.math.abs(x2 - x1)
        val dz = kotlin.math.abs(z2 - z1)
        val sx = if (x1 < x2) 1 else -1
        val sz = if (z1 < z2) 1 else -1
        var err = (if (dx > dz) dx else -dz) / 2
        var e2: Int

        while (true) {
            if (x in 0 until canvasSize && z in 0 until canvasSize) {
                canvas.setPixel(x, z, color)
            }
            if (x == x2 && z == z2) break
            e2 = err
            if (e2 > -dx) { err -= dz; x += sx }
            if (e2 < dz) { err += dx; z += sz }
        }
    }
}
package top.yurikale.landFight.state

import java.util.ArrayDeque

class NetworkGraph {

    // 存储无向边。
    // 永远保证 Pair 的 first < second，这样 (1, 2) 和 (2, 1) 在 Set 中就是同一个对象。
    private val edges = mutableSetOf<Pair<Int, Int>>()

    /**
     * 辅助方法：标准化边，抹平方向性
     */
    private fun normalizeEdge(u: Int, v: Int): Pair<Int, Int> {
        return if (u < v) Pair(u, v) else Pair(v, u)
    }

    /**
     * 建立交通线
     */
    fun addConnection(base1: Int, base2: Int) {
        if (base1 == base2) return // 防止自己连自己的脏数据
        edges.add(normalizeEdge(base1, base2))
    }

    /**
     * 掐断交通线
     */
    fun removeConnection(base1: Int, base2: Int) {
        edges.remove(normalizeEdge(base1, base2))
    }

    /**
     * 获取指定据点的所有直连邻居
     * （因为 N 最大才 30，直接遍历过滤 Set 的性能开销极小，且免去了维护邻接表的麻烦）
     */
    fun getNeighbors(nodeId: Int): List<Int> {
        return edges.mapNotNull { edge ->
            when {
                edge.first == nodeId -> edge.second
                edge.second == nodeId -> edge.first
                else -> null
            }
        }
    }

    /**
     * BFS 连通性搜索：判断 baseId 是否能连通到 targetId (大本营)
     */
    fun isConnected(baseId: Int, targetId: Int): Boolean {
        // 如果查询的就是大本营自己，或者起点终点相同，直接返回 true
        if (baseId == targetId) return true

        // 使用 ArrayDeque 作为队列，性能优于 LinkedList
        val queue = ArrayDeque<Int>()
        val visited = mutableSetOf<Int>()

        queue.add(baseId)
        visited.add(baseId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            // 搜到了目标点，直接掐断循环返回 true
            if (current == targetId) {
                return true
            }

            // 将所有未访问过的相邻据点压入队列
            for (neighbor in getNeighbors(current)) {
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        // 队列跑空了都没找到，说明是断网的孤岛
        return false
    }

    fun clearConnectionsOf(nodeId: Int) {
        edges.removeIf { it.first == nodeId || it.second == nodeId }
    }

    /**
     * 扩展方法（可选）：清空整张网
     * 用于游戏结束、地图重置时调用
     */
    fun clearGraph() {
        edges.clear()
    }
}
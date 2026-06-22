package top.yurikale.landFight.state

enum class GameState {
    LOBBY, // 大厅等待与匹配
    IN_GAME, // 比赛进行中
    RESET // 比赛结束
}
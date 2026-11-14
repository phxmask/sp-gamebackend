
Build a game backend in Kotlin and/or Java, with the following endpoints

## Server 

1. getPlayer/{playerId}
Returns (or creates) aggregated player state:
● Levels: Level ID, level name (from config), and the number of times each level has
been started today by the player.
● Leaderboard: The player's own entry (with two above/below) and the top 10 list
(playerId, position, score, name).
● News: The 5 latest news items (timestamp, message, type).
● Activity: A timestamp of the last active time (updated by all endpoints).
2. startBattle/{playerId}/levelId/{levelId}
● Returns a battleId.
● Increments the daily start count for the level (resetting if it’s a new day)
● Enforces a daily maximum number of battles allowed per level (from config).
3. completeBattle/{playerId}/battleId/{battleId}/score/{score}
● Verifies the battle was started and hasn't been completed.
● Updates the players score in the Leaderboard Service. The score is the total number
of points scored for all levels during this day.
● Returns new leaderboard position


## Clients

Assume that the following external services and endpoints exist (mock them)
Leaderboard Service
- getLeaderboardPosition/{score} - returns the position for a score
- getLeaderboard/{startPosition}/{endPosition} - returns a list of leaderboard entries
{playerId/score}
PlayerProfile Service
- getPlayerDisplayName/{playerId}
News Service
- getNews/{count} - returns a list of latest count news (timestamp, type, message)
Config Service
- getConfig - returns one json containing levels, max attempts per day, etc
The architecture should handle thousands of simultaneous requests per second, with focus
on reliability and performance.

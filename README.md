## Game Backend work test

Implementation of the game backend specified for the work test. 

## Requirements

- JDK 25 

## Running the application

`./gradlew bootRun`

## Running tests

`./gradlew test`

## Server 

The server was implemented using Kotlin and Spring Boot. I tried to keep it as native as possible to kotlin and reduce any external infrastructure dependencies for the sake of this being mostly self contained for review

Expected errors (unknown level, daily limit reached, battle not started) are modeled as typed errors with [Arrow](https://arrow-kt.io/)'s `Either` instead of exceptions, and mapped to HTTP responses in the controllers.

Some liberty was taken due to gaps in the requirements shared. 
 - The service apply its own rules to ties in the returned leaderboard scores, since we have no guarantees that the position returned by the score endpoint is from the requesting player
 - The updateLeaderboard endpoint was added assuming it doesn't return the player position, since it wasn't defined in the assignment alongside the other endpoints of this service
 - Score is validated to be between -100/+100
 - Players at 0 score are not considered to be in the leaderboard
 - Battles started before but completed after the daily reset do not update the leaderboard score 

## Clients

Since I was trying to be more Kotlin native with this project I decided to go with [ktor-client](https://ktor.io/docs/client-create-new-application.html) for the clients instead of the ones provided by Spring Boot.
Retries, timeouts and JSON Serialization/Deserilization were implemented using the respective ktor-client plugins.

For mocking each client when running the application, ktor-client [MockEngine](https://ktor.io/docs/client-testing.html) was used instead of the other available engines. It is the engine used for running tests against ktor-client
but used here as a stand in, as we add fake latency and failures while keeping a somewhat real setup for review.

Most client responses are cached via Caffeine using the Aedile wrapper so that it works nicely with coroutines. 
For certain failures, fallbacks are used so that the player might still interact with the remaining information.

A circuit breaker is implemented via the [HttpCircuitBreaker](https://apidocs.arrow-kt.io/arrow-resilience-ktor-client/arrow.resilience.ktor.client/-http-circuit-breaker/index.html) plugin from Arrow

## Persistence

The Player State is persisted across executions via [LMDB](https://www.symas.com/lmdb), an embedded database. This was mostly picked to reduce dependencies as it then relies only on the kotlin-lmdb lib. 

An alternative on a real environment would be to use something like redis or dynamoDB to scale further

Data is flushed to disk every second for performance purposes, and states older than 2 days are evicted from the DB 

## Load testing

Optionally there is a script under `loadtest` that I used to execute load tests with [k6](https://k6.io/). These were kept simple just to sanity check the setup, since calls are all mocked for this work test.

## Out of scope

- Requests are partially validated but the flow is largely still client authoritative, with this server receiving the score and playerId directly from the caller. No authentication was built for this.
- Infrastructure concerns: As mentioned in the persistence section this can only scale vertically for now.
- Rate limiting

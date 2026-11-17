import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PEAK_RATE = parseInt(__ENV.PEAK_RATE || '1000');
const RAMP_UP = __ENV.RAMP_UP || '30s';
const STEADY = __ENV.STEADY || '1m';
const PLAYERS = parseInt(__ENV.PLAYERS || '100000');
const LEVELS = [1, 2, 3];
const BATTLE_SHARE = 0.3;
const MIN_SCORE = -100;
const MAX_SCORE = 100;

const leaderboardEmpty = new Rate('leaderboard_empty');

export const options = {
    systemTags: ['proto', 'status', 'method', 'name', 'group', 'check', 'error', 'error_code', 'scenario', 'expected_response'],
    scenarios: {
        mixed: {
            executor: 'ramping-arrival-rate',
            startRate: 10,
            timeUnit: '1s',
            preAllocatedVUs: 200,
            maxVUs: 2000,
            stages: [
                { target: PEAK_RATE, duration: RAMP_UP },
                { target: PEAK_RATE, duration: STEADY },
                { target: 0, duration: '10s' },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{endpoint:getPlayer}': ['p(95)<250', 'p(99)<400'],
        'http_req_duration{endpoint:startBattle}': ['p(95)<50', 'p(99)<100'],
        'http_req_duration{endpoint:completeBattle}': ['p(95)<250', 'p(99)<400'],
    },
};

const randomInt = (min, max) => Math.floor(Math.random() * (max - min + 1)) + min;

export default function () {
    const playerId = randomInt(1, PLAYERS);

    if (Math.random() < BATTLE_SHARE) {
        battle(playerId);
    } else {
        getPlayer(playerId);
    }
}

function getPlayer(playerId) {
    const res = http.get(`${BASE_URL}/getPlayer/${playerId}`, { tags: { endpoint: 'getPlayer', name: 'getPlayer' } });
    check(res, { 'getPlayer is 200': (r) => r.status === 200 });

    if (res.status === 200) {
        leaderboardEmpty.add(res.json('leaderboard').length === 0);
    }
}

function battle(playerId) {
    const levelId = LEVELS[randomInt(0, LEVELS.length - 1)];
    const start = http.post(`${BASE_URL}/startBattle/${playerId}/levelId/${levelId}`, null, {
        tags: { endpoint: 'startBattle', name: 'startBattle' },
        responseCallback: http.expectedStatuses(200, 409),
    });
    check(start, { 'startBattle is 200 or 409': (r) => r.status === 200 || r.status === 409 });

    if (start.status !== 200) {
        return;
    }

    const score = randomInt(MIN_SCORE, MAX_SCORE);
    const complete = http.post(`${BASE_URL}/completeBattle/${playerId}/battleId/${start.body}/score/${score}`, null, {
        tags: { endpoint: 'completeBattle', name: 'completeBattle' },
    });
    check(complete, { 'completeBattle is 200': (r) => r.status === 200 });
}

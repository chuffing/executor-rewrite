package com.icbc.nbfh.common.Enum;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ScriptType {

    STOP_REDIS("stop-redis.sh"),
    START_REDIS("start-redis.sh"),
    STOP_SENTINEL("stop-sentinel.sh"),
    START_SENTINEL("start-sentinel.sh"),
    DEPLOY_VERSION("deploy-version.sh"),
    SWITCH_VERSION("switch-version.sh");

    private final String fileName;
}

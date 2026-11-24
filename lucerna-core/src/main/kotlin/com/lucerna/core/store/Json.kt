package com.lucerna.core.store

import kotlinx.serialization.json.Json

/** 全项目共用的 JSON 配置。 */
internal val lucernaJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

package com.atuy.hoyodashboard.api

import java.io.IOException
import java.net.SocketTimeoutException
import org.json.JSONException

fun japaneseApiError(error: Throwable): String {
    val code = (error as? HoYoApiException)?.code
        ?: Regex("\\((-?\\d+)\\)$").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    val status = (error as? HoYoApiException)?.httpStatus
        ?: Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    if (code != null) {
        val message = when (code) {
            -100, -101, 10001, -1071 -> "ログイン情報の有効期限が切れました。もう一度ログインしてください"
            -110, 1028, 10101, -500004, -119, -3006 -> "リクエストが多すぎます。時間をおいて再試行してください"
            -108 -> "指定した言語には対応していません"
            10102 -> "ゲーム記録が非公開です。公開設定を確認してください"
            10104 -> "このリアルタイムノートを閲覧する権限がありません"
            10103, 1008, 1009, -10002, -1073 -> "ゲームアカウントが見つかりません"
            10035, 5003, 10041, 1034, -3101, -3102 -> "本人確認が必要です"
            -5003 -> "今日のチェックインは完了しています"
            -1065, -2003, -2004 -> "交換コードを確認してください"
            -2001 -> "交換コードの有効期限が切れています"
            -2006 -> "交換コードの利用上限に達しました"
            -2008 -> "このサーバーでは交換コードを使えません"
            -2014 -> "交換コードの利用開始前です"
            -2016 -> "少し時間をおいてコードを交換してください"
            -2017, -2018 -> "この交換コードは使用済みです"
            -2021, -2011 -> "コード交換に必要なゲーム内レベルに達していません"
            -3004, -3208, -216, -202 -> "アカウント名またはパスワードを確認してください"
            -3202 -> "アカウントにロックがかかっています"
            -3203 -> "アカウントが見つかりません"
            -3205 -> "確認コードが正しくありません"
            -3206 -> "確認コードの送信回数の上限に達しました"
            -500001 -> "育成計算の入力を確認してください"
            -502001 -> "このキャラクターを所持していません"
            -502002 -> "育成計算の所持データ連携を有効にしてください"
            -1, 10307 -> "サーバーから記録を取得できませんでした"
            else -> "サーバーがリクエストを処理できませんでした（エラー番号 $code）"
        }
        return message
    }
    val message = error.message.orEmpty()
    return when {
        message.any { it in '\u3040'..'\u30ff' } -> message
        status == 401 || status == 403 -> "サーバーがアクセスを許可しませんでした（$status）"
        status == 429 -> "リクエストが多すぎます。少し時間をおいて再試行してください"
        status in 500..599 -> "サーバーでエラーが発生しました（$status）"
        status != null -> "通信に失敗しました（$status）"
        error is SocketTimeoutException -> "通信がタイムアウトしました"
        error is IOException -> "ネットワークに接続できませんでした"
        error is JSONException -> "サーバーの応答を読み取れませんでした"
        error is IllegalArgumentException -> "入力内容を確認してください"
        else -> "情報を取得できませんでした"
    }
}

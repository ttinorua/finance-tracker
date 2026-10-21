package com.financetracker.app.data.ai.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The AI advisor's own choices: which ChatGPT model to use (null = Auto, the model ChatGPT
 * offers apps first), whether every answer is read aloud in the chat, and whether voice mode
 * listens again after each answer.
 */
object AssistantPrefs {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_MODEL = "assistant_chatgpt_model"
    private const val KEY_READ_ALOUD = "assistant_read_aloud"
    private const val KEY_HANDS_FREE = "assistant_hands_free"

    private var prefs: android.content.SharedPreferences? = null
    private val _chatGptModel = MutableStateFlow<String?>(null)
    val chatGptModel: StateFlow<String?> = _chatGptModel.asStateFlow()
    private val _readAloud = MutableStateFlow(false)
    val readAloud: StateFlow<Boolean> = _readAloud.asStateFlow()
    private val _handsFree = MutableStateFlow(true)
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _chatGptModel.value = p.getString(KEY_MODEL, null)
        _readAloud.value = p.getBoolean(KEY_READ_ALOUD, false)
        _handsFree.value = p.getBoolean(KEY_HANDS_FREE, true)
    }

    fun setChatGptModel(slug: String?) {
        _chatGptModel.value = slug
        prefs?.edit()?.apply { if (slug == null) remove(KEY_MODEL) else putString(KEY_MODEL, slug) }?.apply()
    }

    fun setReadAloud(on: Boolean) {
        _readAloud.value = on
        prefs?.edit()?.putBoolean(KEY_READ_ALOUD, on)?.apply()
    }

    fun setHandsFree(on: Boolean) {
        _handsFree.value = on
        prefs?.edit()?.putBoolean(KEY_HANDS_FREE, on)?.apply()
    }
}

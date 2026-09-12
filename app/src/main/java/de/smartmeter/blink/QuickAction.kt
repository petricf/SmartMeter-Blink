package de.smartmeter.blink

import android.content.Context

/**
 * The settings on the E320 reachable after the PIN is unlocked.
 *
 * Menu order after unlock (each short flash scrolls one step, starting at P):
 *
 *   0 P        Momentanleistung (current power)
 *   1 E        own-period consumption
 *   2 E CLr    clear own period            — never long-flash here
 *   3 1d       last 24 h
 *   4 7d       last 7 days
 *   5 30d      last 30 days
 *   6 365d     last 365 days
 *   7 HiS CLr  clear history               — never long-flash here
 *   8 inF      optical info interface  on/oFF   (long flash toggles)
 *   9 Pin      PIN protection      on/oFF       (long flash toggles)
 *   (next) returns to the base display
 *
 * A long flash (~5 s) toggles the item currently shown. Long flashes are only
 * safe on `P`, `inF` and `Pin` — on the CLr items they wipe stored values.
 */
enum class QuickAction(
    val id: String,
    val label: String,
    val menuIndex: Int,
    val defaultScrollsFromPower: Int,
    val description: String,
) {
    OPTICAL(
        "optical",
        "Optical interface (inF)",
        menuIndex = 8,
        defaultScrollsFromPower = 8,
        description = "Toggles the extra data the meter emits on its optical " +
            "Info interface (inF on \u2194 oFF). Needed to read live power/energy " +
            "values with an optical probe or sensor.",
    ),
    PIN(
        "pin",
        "PIN requirement (Pin)",
        menuIndex = 9,
        defaultScrollsFromPower = 9,
        description = "Toggles PIN protection (Pin on \u2194 oFF). Set it to oFF so " +
            "the PIN is no longer requested each time (e.g. in a single-family home).",
    );

    companion object {
        fun byId(id: String?): QuickAction? = entries.firstOrNull { it.id == id }
    }

    /** Localized display name. */
    fun label(context: Context): String = context.getString(
        when (this) {
            OPTICAL -> R.string.qa_inf_title
            PIN -> R.string.qa_pin_title
        },
    )
}
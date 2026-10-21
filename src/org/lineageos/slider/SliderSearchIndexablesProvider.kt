/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.ServiceManager
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_CLASS_NAME
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_ACTION
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_CLASS
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_KEY
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_KEYWORDS
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_RANK
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_SCREEN_TITLE
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_SUMMARY_ON
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_TITLE
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_USER_ID
import android.provider.SearchIndexablesContract.INDEXABLES_RAW_COLUMNS
import android.provider.SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS
import android.provider.SearchIndexablesContract.NON_INDEXABLES_KEYS_COLUMNS
import android.provider.SearchIndexablesProvider
import vendor.lineage.slider.ISlider

// Only the screen is indexed, since the count, the master switch and the
// preset each get a say in whether a position's row is shown at all.
class SliderSearchIndexablesProvider : SearchIndexablesProvider() {
    override fun onCreate() = true

    override fun queryXmlResources(projection: Array<String?>?): Cursor =
        MatrixCursor(INDEXABLES_XML_RES_COLUMNS)

    override fun queryNonIndexableKeys(projection: Array<String?>?): Cursor =
        MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)

    override fun queryRawData(projection: Array<String?>?): Cursor {
        val cursor = MatrixCursor(INDEXABLES_RAW_COLUMNS)
        val context = context ?: return cursor
        // A product can ship the app without a slider, and a result that opens
        // an empty screen is worse than no result.
        if (ServiceManager.getDeclaredInstances(ISlider.DESCRIPTOR).isEmpty()) {
            return cursor
        }

        val screen = context.getString(R.string.slider_settings_title)

        val row = arrayOfNulls<Any>(INDEXABLES_RAW_COLUMNS.size)
        row[COLUMN_INDEX_RAW_RANK] = RANK
        row[COLUMN_INDEX_RAW_TITLE] = screen
        row[COLUMN_INDEX_RAW_SUMMARY_ON] = context.getString(R.string.slider_summary)
        row[COLUMN_INDEX_RAW_KEYWORDS] = context.getString(R.string.slider_search_keywords)
        row[COLUMN_INDEX_RAW_SCREEN_TITLE] = screen
        row[COLUMN_INDEX_RAW_CLASS_NAME] = null
        row[COLUMN_INDEX_RAW_INTENT_ACTION] = ACTION_SETTINGS
        row[COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE] = context.packageName
        row[COLUMN_INDEX_RAW_INTENT_TARGET_CLASS] = SliderSettingsActivity::class.java.name
        row[COLUMN_INDEX_RAW_KEY] = KEY
        row[COLUMN_INDEX_RAW_USER_ID] = -1
        cursor.addRow(row)

        return cursor
    }

    companion object {
        private const val RANK = 2
        private const val KEY = "slider"
        private const val ACTION_SETTINGS = "com.android.settings.action.EXTRA_SETTINGS"
    }
}

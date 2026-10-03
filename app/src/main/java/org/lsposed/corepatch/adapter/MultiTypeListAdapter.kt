package org.lsposed.corepatch.adapter

import android.app.AlertDialog
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.R
import org.lsposed.corepatch.data.SwitchData
import org.lsposed.corepatch.ui.CustomSwitchLayout
import org.lsposed.corepatch.ui.dp

/**
 * Mixed list of section headers (plain [String] labels, e.g. "Kilit Ekranı" in the reference
 * design) and toggle rows ([SwitchData]). Kept as a flat `List<Any>` rather than introducing a
 * sealed class hierarchy so existing callers that only ever built `List<SwitchData>` keep working
 * unchanged -- a header is simply any [String] element mixed into the same list.
 */
class MultiTypeListAdapter(private val dataSet: List<Any>) : BaseAdapter() {

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_SWITCH = 1
    }

    override fun getCount(): Int = dataSet.size

    override fun getItem(position: Int): Any = dataSet[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (dataSet[position] is String) VIEW_TYPE_HEADER else VIEW_TYPE_SWITCH

    override fun isEnabled(position: Int): Boolean = dataSet[position] !is String

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val context = parent!!.context
        return when (val data = dataSet[position]) {
            is String -> {
                val view = convertView as? TextView ?: TextView(context).apply {
                    setTextColor(context.getColor(R.color.section_header))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(20.dp, 24.dp, 20.dp, 8.dp)
                }
                view.text = data
                view
            }

            is SwitchData -> {
                val view = convertView as? CustomSwitchLayout ?: CustomSwitchLayout(context)
                view.titleView.text = data.title
                view.subtitleView.text = data.description

                view.setOnCheckListener {}
                view.switchView.isChecked = Config.getConfig(data.key)
                view.setOnCheckListener { isChecked ->
                    Config.setConfig(data.key, isChecked)
                    if (isChecked && data.warning != null) {
                        AlertDialog.Builder(context)
                            .setMessage(data.warning)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                view
            }

            else -> error("Unsupported item type: $data")
        }
    }
}

package org.lsposed.corepatch.adapter

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.data.SwitchData
import org.lsposed.corepatch.ui.CustomSwitchLayout

class MultiTypeListAdapter(private val dataSet: List<SwitchData>) : BaseAdapter() {
    override fun getCount(): Int {
        return dataSet.size
    }

    override fun getItem(position: Int): SwitchData {
        return dataSet[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val context = parent!!.context
        val data = dataSet[position]
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
        return view
    }
}

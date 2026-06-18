package app.untethered.helper

import app.untethered.data.AppModel

interface AppFilterHelper {
    fun onAppFiltered(items:List<AppModel>)
}
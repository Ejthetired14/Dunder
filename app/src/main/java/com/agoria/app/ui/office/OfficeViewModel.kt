package com.agoria.app.ui.office

import androidx.lifecycle.ViewModel
import com.agoria.app.ui.office.world.CameraState
import com.agoria.app.ui.office.world.OfficeSim

class OfficeViewModel : ViewModel() {
    val sim = OfficeSim()
    val camera = CameraState()

    override fun onCleared() {
        super.onCleared()
        // Clean up if needed
    }
}
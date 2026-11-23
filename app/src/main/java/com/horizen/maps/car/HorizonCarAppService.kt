package com.horizen.maps.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import androidx.car.app.model.CarText
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

class HorizonCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return HorizonCarSession()
    }
}

class HorizonCarSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen {
        return HorizonCarScreen(carContext)
    }
}

class HorizonCarScreen(carContext: androidx.car.app.CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder(
            CarText.create("The Android Auto connection is installed. The map screen and turn-by-turn guidance are the next development step.")
        )
            .setTitle("HorizonMaps")
            .build()
    }
}

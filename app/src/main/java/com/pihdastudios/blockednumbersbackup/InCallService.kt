/*
 * Copyright (c) 2025. Pihdastudios
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.pihdastudios.blockednumbersbackup

import android.app.Service
import android.content.Intent
import android.os.IBinder

class InCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        // Dummy, so Android would detect this as a valid Dialer Role
        return null
    }
}

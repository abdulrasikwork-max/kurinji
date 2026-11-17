package com.safesphere.app.core.media

import android.content.Context

/**
 * The app's ONE file provider (`res/xml/camera_paths.xml`): the camera app may write only into two cache folders,
 * the ID photo's (N3) and a moment's photo (N4, A2). Not exported; each photo's access is granted per
 * `TakePicture` intent. No CAMERA permission exists in the app.
 */
object CameraFiles {
    fun authority(context: Context) = "${context.packageName}.camera"
}

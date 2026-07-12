package com.sloopworks.dayfold.android

import android.app.Application
import com.sloopworks.dayfold.client.AppState
import org.reduxkotlin.Store
import org.reduxkotlin.StoreEnhancer

// Release: analytics is debug/internal-only (ADR 0055). Inert mirror → zero swip-analytics bytes.
@Suppress("UNUSED_PARAMETER")
fun swipInit(app: Application) = Unit

fun debugStoreEnhancer(): StoreEnhancer<AppState>? = null

@Suppress("UNUSED_PARAMETER")
fun swipLifecycleInstall(app: Application, store: Store<AppState>) = Unit

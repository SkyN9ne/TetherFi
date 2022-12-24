package com.pyamsoft.tetherfi.status

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CheckResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.notify.NotifyGuard
import com.pyamsoft.pydroid.ui.navigator.FragmentNavigator
import com.pyamsoft.pydroid.ui.theme.Theming
import com.pyamsoft.pydroid.ui.theme.asThemeProvider
import com.pyamsoft.pydroid.ui.util.dispose
import com.pyamsoft.pydroid.ui.util.recompose
import com.pyamsoft.pydroid.util.PermissionRequester
import com.pyamsoft.pydroid.util.doOnResume
import com.pyamsoft.tetherfi.ObjectGraph
import com.pyamsoft.tetherfi.R
import com.pyamsoft.tetherfi.TetherFiTheme
import com.pyamsoft.tetherfi.server.ServerNetworkBand
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.service.foreground.NotificationRefreshEvent
import com.pyamsoft.tetherfi.tile.ProxyTileService
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class StatusFragment : Fragment(), FragmentNavigator.Screen<com.pyamsoft.tetherfi.main.MainView> {

  @JvmField @Inject internal var viewModel: StatusViewModeler? = null
  @JvmField @Inject internal var theming: Theming? = null

  @JvmField @Inject internal var notifyGuard: NotifyGuard? = null

  @JvmField
  @Inject
  @Named("server")
  internal var serverPermissionRequester: PermissionRequester? = null

  @JvmField
  @Inject
  @Named("notification")
  internal var notificationPermissionRequester: PermissionRequester? = null

  @JvmField @Inject internal var notificationRefreshBus: EventBus<NotificationRefreshEvent>? = null

  private var serverRequester: PermissionRequester.Requester? = null
  private var notificationRequester: PermissionRequester.Requester? = null

  private fun onToggleProxy() {
    viewModel.requireNotNull().handleToggleProxy(scope = viewLifecycleOwner.lifecycleScope)
  }

  private fun onSsidChanged(ssid: String) {
    viewModel
        .requireNotNull()
        .handleSsidChanged(
            scope = viewLifecycleOwner.lifecycleScope,
            ssid = ssid.trim(),
        )
  }

  private fun onPasswordChanged(password: String) {
    viewModel
        .requireNotNull()
        .handlePasswordChanged(
            scope = viewLifecycleOwner.lifecycleScope,
            password = password,
        )
  }

  private fun onPortChanged(port: String) {
    viewModel
        .requireNotNull()
        .handlePortChanged(
            scope = viewLifecycleOwner.lifecycleScope,
            port = port,
        )
  }

  private fun safeOpenSettingsIntent(action: String) {
    val act = requireActivity()

    // Try specific first, may fail on some devices
    try {
      val intent = Intent(action, "package:${act.packageName}".toUri())
      act.startActivity(intent)
    } catch (e: Throwable) {
      Timber.e(e, "Failed specific intent for $action")
      val intent = Intent(action)
      act.startActivity(intent)
    }
  }

  private fun onOpenBatterySettings() {
    safeOpenSettingsIntent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
  }

  private fun onToggleProxyWakelock() {
    viewModel.requireNotNull().handleToggleProxyWakelock(scope = viewLifecycleOwner.lifecycleScope)
  }

  private fun onChangeBand(band: ServerNetworkBand) {
    viewModel
        .requireNotNull()
        .handleChangeBand(
            scope = viewLifecycleOwner.lifecycleScope,
            band = band,
        )
  }

  private fun onRequestServerPermissions() {
    viewModel.requireNotNull().also { vm ->
      // Close dialog
      vm.handlePermissionsExplained()

      // Request permissions
      serverRequester.requireNotNull().requestPermissions()
    }
  }

  private fun onOpenApplicationSettings() {
    // Close dialog
    viewModel.requireNotNull().handlePermissionsExplained()

    // Open settings
    safeOpenSettingsIntent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
  }

  private fun registerPermissionRequests(notificationPermissionState: MutableState<Boolean>) {
    serverRequester?.unregister()
    serverRequester =
        serverPermissionRequester.requireNotNull().registerRequester(this) { granted ->
          if (granted) {
            Timber.d("Network permission granted, toggle proxy")
            onToggleProxy()
          } else {
            Timber.w("Network permission not granted")
          }
        }

    notificationRequester?.unregister()
    notificationRequester =
        notificationPermissionRequester.requireNotNull().registerRequester(this) { granted ->
          if (granted) {
            Timber.d("Notification permission granted")

            // Broadcast in the background
            viewLifecycleOwner.lifecycleScope.launch(context = Dispatchers.IO) {
              notificationRefreshBus.requireNotNull().send(NotificationRefreshEvent)
            }
          } else {
            Timber.w("Notification permission not granted")
          }
          notificationPermissionState.value = granted
        }
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
  ): View {
    val act = requireActivity()

    ObjectGraph.ActivityScope.retrieve(act).plusStatus().create().inject(this)

    val vm = viewModel.requireNotNull()
    val appName = act.getString(R.string.app_name)
    val ng = notifyGuard.requireNotNull()

    // Hold the state here locally so we don't carry outside of Composable lifespan
    val notificationState = mutableStateOf(ng.canPostNotification())

    // As early as possible because of Lifecycle quirks
    registerPermissionRequests(notificationState)

    // Also hold onto the requester here instead of in composition
    val npr = notificationRequester.requireNotNull()

    val themeProvider = act.asThemeProvider(theming.requireNotNull())
    return ComposeView(act).apply {
      id = R.id.screen_status

      setContent {
        val handleToggleProxy by rememberUpdatedState { onToggleProxy() }

        val handleSsidChange by rememberUpdatedState { ssid: String -> onSsidChanged(ssid) }

        val handlePasswordChange by rememberUpdatedState { password: String ->
          onPasswordChanged(password)
        }

        val handlePortChanged by rememberUpdatedState { port: String -> onPortChanged(port) }

        val handleOpenBatterySettings by rememberUpdatedState { onOpenBatterySettings() }

        val handleDismissPermissionExplanation by rememberUpdatedState {
          vm.handlePermissionsExplained()
        }

        val handleRequestPermissions by rememberUpdatedState { onRequestServerPermissions() }

        val handleOpenPermissionSettings by rememberUpdatedState { onOpenApplicationSettings() }

        val handleToggleKeepWakeLock by rememberUpdatedState { onToggleProxyWakelock() }

        val handleSelectBand by rememberUpdatedState { band: ServerNetworkBand ->
          onChangeBand(band)
        }

        val handleRequestNotificationPermission by rememberUpdatedState { npr.requestPermissions() }

        val handleStatusUpdated by rememberUpdatedState { _: RunningStatus ->
          ProxyTileService.updateTile(act)
        }

        act.TetherFiTheme(themeProvider) {
          StatusScreen(
              modifier = Modifier.fillMaxSize(),
              state = vm.state(),
              appName = appName,
              hasNotificationPermission = notificationState.value,
              onToggle = handleToggleProxy,
              onSsidChanged = handleSsidChange,
              onPasswordChanged = handlePasswordChange,
              onPortChanged = handlePortChanged,
              onOpenBatterySettings = handleOpenBatterySettings,
              onDismissPermissionExplanation = handleDismissPermissionExplanation,
              onRequestPermissions = handleRequestPermissions,
              onOpenPermissionSettings = handleOpenPermissionSettings,
              onToggleKeepWakeLock = handleToggleKeepWakeLock,
              onSelectBand = handleSelectBand,
              onRequestNotificationPermission = handleRequestNotificationPermission,
              onStatusUpdated = handleStatusUpdated,
          )
        }
      }
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    viewModel.requireNotNull().also { vm ->
      vm.restoreState(savedInstanceState)
      vm.refreshGroupInfo(scope = viewLifecycleOwner.lifecycleScope)
      vm.watchStatusUpdates(scope = viewLifecycleOwner.lifecycleScope)

      vm.loadPreferences(scope = viewLifecycleOwner.lifecycleScope) {
        // Vitals
        viewLifecycleOwner.doOnResume { requireActivity().reportFullyDrawn() }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.requireNotNull().refreshSystemInfo(scope = viewLifecycleOwner.lifecycleScope)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    viewModel?.saveState(outState)
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    recompose()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    dispose()

    serverPermissionRequester = null
    notificationPermissionRequester = null
    theming = null
    viewModel = null
    notifyGuard = null
    notificationRefreshBus = null

    serverRequester?.unregister()
    serverRequester = null

    notificationRequester?.unregister()
    notificationRequester = null
  }

  override fun getScreenId(): com.pyamsoft.tetherfi.main.MainView {
    return com.pyamsoft.tetherfi.main.MainView.Status
  }

  companion object {

    @JvmStatic
    @CheckResult
    fun newInstance(): Fragment {
      return StatusFragment().apply { arguments = Bundle().apply {} }
    }
  }
}

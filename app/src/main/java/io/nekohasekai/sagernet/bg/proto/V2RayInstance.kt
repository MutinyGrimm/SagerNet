/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.proto

import android.os.Build
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ShadowsocksProvider
import io.nekohasekai.sagernet.TrojanProvider
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.bg.ExternalInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.V2rayBuildResult
import io.nekohasekai.sagernet.fmt.brook.BrookBean
import io.nekohasekai.sagernet.fmt.brook.internalUri
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteriaConfig
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.pingtunnel.PingTunnelBean
import io.nekohasekai.sagernet.fmt.relaybaton.RelayBatonBean
import io.nekohasekai.sagernet.fmt.relaybaton.buildRelayBatonConfig
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildShadowsocksConfig
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan.buildTrojanConfig
import io.nekohasekai.sagernet.fmt.trojan.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildCustomTrojanConfig
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import libcore.V2RayInstance
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

abstract class V2RayInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: V2rayBuildResult
    lateinit var v2rayPoint: V2RayInstance
    private lateinit var wsForwarder: WebView

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    var closed by AtomicBoolean()
    fun isInitialized(): Boolean {
        return ::config.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildV2RayConfig(profile)
    }

    protected open fun loadConfig() {
        v2rayPoint.loadConfig(config.config, false)
    }

    open fun init() {
        v2rayPoint = V2RayInstance()
        buildConfig()
        for ((isBalancer, chain) in config.index) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val needChain = !isBalancer && index != chain.size - 1
                val mux = DataStore.enableMux && (isBalancer || chain.size == 0)
                val bean = profile.requireBean()

                when {
                    bean is ShadowsocksBean -> when (profile.pickShadowsocksProvider()) {
                        ShadowsocksProvider.CLASH -> {
                            externalInstances[port] = ShadowsocksInstance(bean, port)
                        }
                        else -> {
                            pluginConfigs[port] = profile.type to bean.buildShadowsocksConfig(
                                port
                            )
                        }
                    }
                    bean is ShadowsocksRBean -> {
                        externalInstances[port] = ShadowsocksRInstance(bean, port)
                    }
                    bean is TrojanBean -> {
                        when (DataStore.providerTrojan) {
                            TrojanProvider.TROJAN -> {
                                initPlugin("trojan-plugin")
                                pluginConfigs[port] = profile.type to bean.buildTrojanConfig(
                                    port
                                )
                            }
                            TrojanProvider.TROJAN_GO -> {
                                initPlugin("trojan-go-plugin")
                                pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(
                                    port, mux
                                )
                            }
                        }
                    }
                    bean is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(
                            port, mux
                        )
                    }
                    bean is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }
                    bean is PingTunnelBean -> {
                        if (needChain) error("PingTunnel is incompatible with chain")
                        initPlugin("pingtunnel-plugin")
                    }
                    bean is RelayBatonBean -> {
                        initPlugin("relaybaton-plugin")
                        pluginConfigs[port] = profile.type to bean.buildRelayBatonConfig(port)
                    }
                    bean is BrookBean -> {
                        initPlugin("brook-plugin")
                    }
                    bean is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteriaConfig(port) {
                            File(
                                app.noBackupFilesDir,
                                "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                    bean is ConfigBean -> {
                        when (bean.type) {
                            "trojan-go" -> {
                                initPlugin("trojan-go-plugin")
                                pluginConfigs[port] = profile.type to buildCustomTrojanConfig(
                                    bean.content, port
                                )
                            }
                            else -> {
                                externalInstances[port] = ExternalInstance(
                                    profile, port
                                ).apply {
                                    init()
                                }
                            }
                        }
                    }
                    bean is SOCKSBean -> {
                        externalInstances[port] = Socks4To5Instance(bean, port)
                    }
                    bean is SnellBean -> {
                        externalInstances[port] = SnellInstance(bean, port)
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        val context = if (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked) SagerNet.application else SagerNet.deviceStorage

        for ((isBalancer, chain) in config.index) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = !isBalancer && index != chain.size - 1
                val config = pluginConfigs[port]?.second ?: ""

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }
                    bean is ShadowsocksBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "shadowsocks_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            File(
                                SagerNet.application.applicationInfo.nativeLibraryDir,
                                Executable.SS_LOCAL
                            ).absolutePath, "-c", configFile.absolutePath, "--log-without-time"
                        )

                        if (DataStore.enableLog) commands.add("-v")

                        processes.start(commands)
                    }
                    bean is TrojanBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "trojan_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = listOf(
                            when (DataStore.providerTrojan) {
                                TrojanProvider.TROJAN -> initPlugin("trojan-plugin")
                                else -> initPlugin("trojan-go-plugin")
                            }.path, "--config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }
                    bean is TrojanGoBean || bean is ConfigBean && bean.type == "trojan-go" -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }
                    bean is NaiveBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands)
                    }
                    bean is PingTunnelBean -> {
                        if (needChain) error("PingTunnel is incompatible with chain")

                        val commands = mutableListOf(
                            "su",
                            "-c",
                            initPlugin("pingtunnel-plugin").path,
                            "-type",
                            "client",
                            "-sock5",
                            "1",
                            "-l",
                            "$LOCALHOST:$port",
                            "-s",
                            bean.serverAddress
                        )

                        if (bean.key.isNotBlank() && bean.key != "1") {
                            commands.add("-key")
                            commands.add(bean.key)
                        }

                        processes.start(commands)
                    }
                    bean is RelayBatonBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "rb_" + SystemClock.elapsedRealtime() + ".toml"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("relaybaton-plugin").path,
                            "client",
                            "--config",
                            configFile.absolutePath
                        )

                        processes.start(commands)
                    }
                    bean is BrookBean -> {
                        val commands = mutableListOf(initPlugin("brook-plugin").path)

                        when (bean.protocol) {
                            "ws" -> {
                                commands.add("wsclient")
                                commands.add("--wsserver")
                            }
                            "wss" -> {
                                commands.add("wssclient")
                                commands.add("--wssserver")
                            }
                            else -> {
                                commands.add("client")
                                commands.add("--server")
                            }
                        }

                        commands.add(bean.internalUri())

                        if (bean.password.isNotBlank()) {
                            commands.add("--password")
                            commands.add(bean.password)
                        }

                        commands.add("--socks5")
                        commands.add("$LOCALHOST:$port")

                        processes.start(commands)
                    }
                    bean is HysteriaBean -> {
                        val configFile = File(
                            context.noBackupFilesDir,
                            "hysteria_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.enableLog) "trace" else "warn",
                            "client"
                        )

                        processes.start(commands)
                    }
                }
            }
        }

        v2rayPoint.start()

        if (config.requireWs) {
            val url = "http://$LOCALHOST:" + (config.wsPort) + "/"

            runOnMainDispatcher {
                wsForwarder = WebView(context)
                wsForwarder.settings.javaScriptEnabled = true
                wsForwarder.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Logs.d("WebView load failed: $error")

                        runOnMainDispatcher {
                            wsForwarder.loadUrl("about:blank")

                            delay(1000L)
                            wsForwarder.loadUrl(url)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)

                        Logs.d("WebView loaded: ${view.title}")

                    }
                }
                wsForwarder.loadUrl(url)
            }
        }

    }

    fun destroy() {
        runBlocking { onMainDispatcher { destroy(this) } }
    }

    override fun destroy(scope: CoroutineScope) {
        for (instance in externalInstances.values) {
            instance.destroy(scope)
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::wsForwarder.isInitialized) {
            wsForwarder.loadUrl("about:blank")
            wsForwarder.destroy()
        }

        if (::processes.isInitialized) {
            processes.close(scope)
        }

        if (::v2rayPoint.isInitialized) {
            v2rayPoint.close()
        }
    }

}
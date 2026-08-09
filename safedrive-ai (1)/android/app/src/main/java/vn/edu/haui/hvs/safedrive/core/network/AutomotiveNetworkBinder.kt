package vn.edu.haui.hvs.safedrive.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Binds AAOS processes to their Internet-capable WLAN when the platform exposes overlapping
 * vehicle-Ethernet and Wi-Fi subnets. Some CarSky images publish both interfaces as 10.0.2.0/24;
 * an unmarked application socket can then be routed through the isolated vehicle interface even
 * though Android's WLAN network has a working gateway. Phones are deliberately left untouched.
 */
object AutomotiveNetworkBinder {
    fun bindInternetWlan(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork
            ?.takeIf { connectivityManager.isInternetWlan(it) }
            ?: connectivityManager.allNetworks.firstOrNull { connectivityManager.isInternetWlan(it) }
            ?: return false

        return connectivityManager.bindProcessToNetwork(network)
    }

    private fun ConnectivityManager.isInternetWlan(network: Network): Boolean {
        val capabilities = getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

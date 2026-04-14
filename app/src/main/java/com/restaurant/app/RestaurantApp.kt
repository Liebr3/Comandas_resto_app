package com.restaurant.app



import android.os.Bundle
import android.content.Intent
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ═══════════════════════════════════════════════════════════════
// DATASTORE — PERSISTENCIA LOCAL
// ═══════════════════════════════════════════════════════════════

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "restaurant_prefs")

object PrefKeys {
    val PLATOS_DEL_DIA       = stringPreferencesKey("platos_del_dia")
    val GUARNICIONES_DEL_DIA = stringPreferencesKey("guarniciones_del_dia")
    val RESTAURANT_NAME      = stringPreferencesKey("restaurant_name")
    val FOOTER_TEXT          = stringPreferencesKey("footer_text")
    val TOP_MARGIN           = stringPreferencesKey("top_margin")
    val BOTTOM_MARGIN        = stringPreferencesKey("bottom_margin")
    val COLA_OFFLINE         = stringPreferencesKey("cola_offline")
}

// ═══════════════════════════════════════════════════════════════
// COLA OFFLINE
// ═══════════════════════════════════════════════════════════════

enum class TipoOperacion {
    CREAR_COMANDA,
    ACTUALIZAR_ESTADO,
    ASIGNAR_MESA,
    AGREGAR_ITEM,
    ELIMINAR_ITEM
}

data class OperacionPendiente(
    val id: String = java.util.UUID.randomUUID().toString(),
    val tipo: TipoOperacion,
    val payload: String  // JSON serializado con los parámetros
)

// Serialización simple a JSON
fun OperacionPendiente.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("tipo", tipo.name)
    put("payload", payload)
}

fun operacionPendienteFromJson(json: JSONObject) = OperacionPendiente(
    id      = json.getString("id"),
    tipo    = TipoOperacion.valueOf(json.getString("tipo")),
    payload = json.getString("payload")
)


// ═══════════════════════════════════════════════════════════════
// MODELOS DE DATOS
// ═══════════════════════════════════════════════════════════════

data class MenuItem(
    val id: Int,
    val name: String,
    val description: String,
    val price: Double,
    val category: String,
    val isAvailable: Boolean
)

data class Table(
    val id: Int,
    val name: String,
    val capacity: Int
)

data class OrderItem(
    val menuItem: MenuItem,
    val quantity: Int,
    val notes: String = "",
    val unitPriceOverride: Double? = null
)

data class Order(
    val orderId: Int,
    val tableNumber: String,
    val status: String,
    val total: Double,
    val createdAt: String,
    val items: List<OrderItemDetail>
)

data class OrderItemDetail(
    val id: Int,
    val name: String,
    val quantity: Int,
    val unitPrice: Double,
    val subtotal: Double,
    val notes: String
)

data class HistorialEntry(
    val order: Order,
    val propina: Double,
    val totalConPropina: Double,
    val fechaGuardado: String
)

data class ItemDesglosado(
    val id: String,           // único por fila: "${orderItemId}_${indice}"
    val orderItemId: Int,
    val name: String,
    val unitPrice: Double,
    val notes: String
)

data class PagoGrupo(
    val numero: Int,
    val items: List<ItemDesglosado>,
    val subtotal: Double,
    val propina: Double,
    val total: Double
)

// ═══════════════════════════════════════════════════════════════
// CLIENTE API REST
// ═══════════════════════════════════════════════════════════════

data class ConfigDia(
    val platos: List<String>,
    val guarniciones: List<String>,
    val restaurantName: String,
    val footerText: String,
    val topMargin: Int,
    val bottomMargin: Int,
    val claveSeguridad: String,
    val menuExtra: String
)

class ApiClient(private val baseUrl: String) {

    private suspend fun <T> withRetry(
        attempts: Int = 3,
        delayMs: Long = 2000,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < attempts - 1) kotlinx.coroutines.delay(delayMs)
            }
        }
        throw lastException!!
    }
    suspend fun getMenu(): Result<List<MenuItem>> = runCatching {
        android.util.Log.d("API_DEBUG", "Intentando conectar a: $baseUrl/menu")
        val url = URL("$baseUrl/menu")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        val json = JSONObject(response)
        val data = json.getJSONArray("data")
        List(data.length()) { i ->
            val item = data.getJSONObject(i)
            MenuItem(
                id = item.getInt("id"),
                name = item.getString("name"),
                description = item.optString("description", ""),
                price = item.getDouble("price"),
                category = item.getString("category"),
                isAvailable = item.getInt("is_available") == 1
            )
        }
    }.also { result ->
        result.onFailure { android.util.Log.e("API_DEBUG", "FALLO en getMenu: ${it::class.simpleName}: ${it.message}") }
        result.onSuccess { android.util.Log.d("API_DEBUG", "EXITO: ${it.size} items cargados") }
    }

    suspend fun getTables(): Result<List<Table>> = runCatching {
        val url = URL("$baseUrl/tables")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        val json = JSONObject(response)
        val data = json.getJSONArray("data")
        List(data.length()) { i ->
            val table = data.getJSONObject(i)
            Table(
                id = table.getInt("id"),
                name = table.getString("name"),
                capacity = table.getInt("capacity")
            )
        }
    }

    suspend fun createOrder(tableId: Int, items: List<OrderItem>): Result<String> = runCatching {
        withRetry {
            android.util.Log.d("API_DEBUG", "Creando comanda para mesa $tableId")
            val url = URL("$baseUrl/orders")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val itemsArray = JSONArray()
        items.forEach { orderItem ->
            val itemJson = JSONObject()
            itemJson.put("menu_item_id", orderItem.menuItem.id)
            itemJson.put("quantity", orderItem.quantity)
            itemJson.put("notes", orderItem.notes)
            orderItem.unitPriceOverride?.let { itemJson.put("unit_price", it) }
            itemsArray.put(itemJson)
        }
        val body = JSONObject()
        body.put("table_id", tableId)
        body.put("items", itemsArray)
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val responseCode = connection.responseCode
        val response = if (responseCode >= 400) {
            connection.errorStream.bufferedReader().readText()
        } else {
            connection.inputStream.bufferedReader().readText()
        }
        connection.disconnect()
        val json = JSONObject(response)
            json.optString("message", json.optString("error", "Sin respuesta"))
        } // cierra withRetry
    }.also { result ->
        result.onFailure { android.util.Log.e("API_DEBUG", "FALLO en createOrder: ${it::class.simpleName}: ${it.message}") }
    }

    suspend fun getOrders(): Result<List<Order>> = runCatching {
        val url = URL("$baseUrl/orders")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        val json = JSONObject(response)
        val data = json.getJSONArray("data")
        List(data.length()) { i ->
            val order = data.getJSONObject(i)
            val itemsArray = order.getJSONArray("items")
            val items = List(itemsArray.length()) { j ->
                val item = itemsArray.getJSONObject(j)
                OrderItemDetail(
                    id = item.getInt("id"),
                    name = item.getString("name"),
                    quantity = item.getInt("quantity"),
                    unitPrice = item.getDouble("unit_price"),
                    subtotal = item.getDouble("subtotal"),
                    notes = item.optString("notes", "")
                )
            }
            Order(
                orderId = order.getInt("order_id"),
                tableNumber = order.getString("table_number"),
                status = order.getString("status"),
                total = order.getDouble("total"),
                createdAt = order.getString("created_at"),
                items = items
            )
        }
    }

    suspend fun updateOrderStatus(orderId: Int, status: String): Result<String> = runCatching {
        withRetry {
            val url = URL("$baseUrl/orders/$orderId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val body = JSONObject()
        body.put("status", status)
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        val json = JSONObject(response)
            json.getString("message")
        } // cierra withRetry
    }

    suspend fun assignTableAndStart(orderId: Int, tableId: Int): Result<String> = runCatching {
        withRetry {
            val url = URL("$baseUrl/orders/$orderId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PATCH"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val body = JSONObject()
        body.put("status", "in_progress")
        body.put("table_id", tableId)
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val responseCode = connection.responseCode
        val response = if (responseCode >= 400) {
            connection.errorStream.bufferedReader().readText()
        } else {
            connection.inputStream.bufferedReader().readText()
        }
        connection.disconnect()
        val json = JSONObject(response)
            json.optString("message", json.optString("error", "Sin respuesta"))
        } // cierra withRetry
    }

    suspend fun reassignTable(orderId: Int, tableId: Int): Result<String> = runCatching {
        val url = URL("$baseUrl/orders/$orderId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "PATCH"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val body = JSONObject()
        body.put("table_id", tableId)
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val responseCode = connection.responseCode
        val response = if (responseCode >= 400)
            connection.errorStream.bufferedReader().readText()
        else
            connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        JSONObject(response).optString("message", JSONObject(response).optString("error", "Sin respuesta"))
    }

    suspend fun addItemToOrder(orderId: Int, menuItemId: Int, quantity: Int, notes: String, unitPriceOverride: Double? = null): Result<String> = runCatching {
        withRetry {
            val url = URL("$baseUrl/orders/$orderId/items")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val body = JSONObject()
        body.put("menu_item_id", menuItemId)
        body.put("quantity", quantity)
        body.put("notes", notes)
        unitPriceOverride?.let { body.put("unit_price", it) }
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val responseCode = connection.responseCode
        val response = if (responseCode >= 400) {
            connection.errorStream.bufferedReader().readText()
        } else {
            connection.inputStream.bufferedReader().readText()
        }
        connection.disconnect()
        val json = JSONObject(response)
            json.optString("message", json.optString("error", "Sin respuesta"))
        } // cierra withRetry
    }

    suspend fun removeItemFromOrder(orderId: Int, itemId: Int): Result<String> = runCatching {
        withRetry {
            val url = URL("$baseUrl/orders/$orderId/items/$itemId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val responseCode = connection.responseCode
        val response = if (responseCode >= 400) {
            connection.errorStream.bufferedReader().readText()
        } else {
            connection.inputStream.bufferedReader().readText()
        }
        connection.disconnect()
        val json = JSONObject(response)
            json.optString("message", json.optString("error", "Sin respuesta"))
        } // cierra withRetry
    }

    suspend fun printOrder(orderId: Int, orderStatus: String, restaurantName: String, footerText: String, topMargin: Int = 0, bottomMargin: Int = 0): Result<String> = runCatching {
        val url = URL("$baseUrl/orders/$orderId/print")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        val body = JSONObject()
        body.put("restaurant_name", restaurantName)
        body.put("footer_text", footerText)
        body.put("for_kitchen", orderStatus in listOf("pending", "in_progress"))
        body.put("top_margin", topMargin)
        body.put("bottom_margin", bottomMargin)
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val responseCode = connection.responseCode
        val response = if (responseCode >= 400) {
            connection.errorStream.bufferedReader().readText()
        } else {
            connection.inputStream.bufferedReader().readText()
        }
        connection.disconnect()
        val json = JSONObject(response)
        json.optString("message", json.optString("error", "Sin respuesta"))
    }

    suspend fun getConfigDia(): Result<ConfigDia> = runCatching {
        val url = URL("$baseUrl/config/dia")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        val json = JSONObject(response)
        val data = json.getJSONObject("data")
        val platos = data.getJSONArray("platos_del_dia").let { arr ->
            List(arr.length()) { arr.getString(it) }
        }
        val guarniciones = data.getJSONArray("guarniciones_del_dia").let { arr ->
            List(arr.length()) { arr.getString(it) }
        }
        val restaurantName = data.optString("restaurant_name", "Mi Restaurante")
        val footerText     = data.optString("footer_text", "Gracias por su visita")
        val topMargin      = data.optInt("top_margin", 0)
        val bottomMargin   = data.optInt("bottom_margin", 0)
        val claveSeguridad = data.optString("clave_seguridad", "1234")
        val menuExtra      = data.optString("menu_extra", "")
        ConfigDia(platos, guarniciones, restaurantName, footerText, topMargin, bottomMargin, claveSeguridad, menuExtra)
    }

    suspend fun setConfigDia(
        platos: List<String>,
        guarniciones: List<String>,
        restaurantName: String = "",
        footerText: String = "",
        topMargin: Int = -1,
        bottomMargin: Int = -1,
        claveSeguridad: String = "",
        menuExtra: String = ""
    ): Result<String> = runCatching {
        val url = URL("$baseUrl/config/dia")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val body = JSONObject()
        body.put("platos_del_dia", org.json.JSONArray(platos))
        body.put("guarniciones_del_dia", org.json.JSONArray(guarniciones))
        if (restaurantName.isNotEmpty()) body.put("restaurant_name", restaurantName)
        if (footerText.isNotEmpty()) body.put("footer_text", footerText)
        if (topMargin >= 0) body.put("top_margin", topMargin)
        if (bottomMargin >= 0) body.put("bottom_margin", bottomMargin)
        if (claveSeguridad.isNotEmpty()) body.put("clave_seguridad", claveSeguridad)
        if (menuExtra.isNotEmpty()) body.put("menu_extra", menuExtra)
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        JSONObject(response).optString("message", "OK")
    }
    suspend fun printPagoDividido(
        mesaLabel: String,
        grupos: List<PagoGrupo>,
        restaurantName: String,
        footerText: String,
        topMargin: Int = 0,
        bottomMargin: Int = 0
    ): Result<String> = runCatching {
        val url = URL("$baseUrl/print/pago_dividido")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 15000

        val gruposArray = JSONArray()
        grupos.forEach { grupo ->
            val grupoJson = JSONObject()
            grupoJson.put("numero", grupo.numero)
            grupoJson.put("subtotal", grupo.subtotal)
            grupoJson.put("propina", grupo.propina)
            grupoJson.put("total", grupo.total)
            val itemsArray = JSONArray()
            grupo.items.forEach { item ->
                val itemJson = JSONObject()
                itemJson.put("name", item.name)
                itemJson.put("unit_price", item.unitPrice)
                itemJson.put("notes", item.notes)
                itemsArray.put(itemJson)
            }
            grupoJson.put("items", itemsArray)
            gruposArray.put(grupoJson)
        }

        val body = JSONObject()
        body.put("mesa_label", mesaLabel)
        body.put("grupos", gruposArray)
        body.put("restaurant_name", restaurantName)
        body.put("footer_text", footerText)
        body.put("top_margin", topMargin)
        body.put("bottom_margin", bottomMargin)

        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }
        val responseCode = connection.responseCode
        val response = if (responseCode >= 400)
            connection.errorStream.bufferedReader().readText()
        else
            connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        JSONObject(response).optString("message", "OK")
    }

}

// ═══════════════════════════════════════════════════════════════
// VIEWMODEL
// ═══════════════════════════════════════════════════════════════

class RestaurantViewModel(
    private val apiClient: ApiClient,
    private val dataStore: DataStore<Preferences>,
    private val contextoApp: android.content.Context
) : ViewModel() {

    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var menuItems by mutableStateOf(listOf<MenuItem>())
    var tables by mutableStateOf(listOf<Table>())
    var orders by mutableStateOf(listOf<Order>())

    var historial by mutableStateOf(listOf<HistorialEntry>())
    var claveSeguridad by mutableStateOf("1234")

    // Guarniciones configurables del día
    var guarnicionesDelDia by mutableStateOf(listOf<String>())

    // Platos configurables del día
    var platosDelDia by mutableStateOf(listOf<String>())
    var menuExtraDelDia by mutableStateOf("")

    // Configuración de impresión
    var restaurantName by mutableStateOf("Mi Restaurante")
    var footerText by mutableStateOf("Gracias por su visita")
    // Generador de menú semanal
    // Generador de menú semanal
    var platosGenerador by mutableStateOf(listOf(
        "Pastas con salsa alfredo", "Pastas con salsa boloñesa", "Pastas con salsa pesto de perejil",
        "Pastas con salsa McCheesse", "Tallarines de zapallo italiano con salsa alfredo",
        "Tallarines de zapallo italiano con salsa boloñesa", "Budin de zapallo italiano",
        "Lasaña de zapallo italiano", "Lasaña boloñesa", "Pollo asado (trutro)",
        "Strogonoff de pollo", "Quiche Napolitano", "Quiche de champiñon/pollo/choclo",
        "Estofado de cerdo", "Lentejas con longaniza", "Tortilla de atun",
        "Zapallos italianos rellenos", "Chapsui mixto", "Lasaña de berenjenas", "Ramen de pollo"
    ))
    var topMargin by mutableStateOf(0)
    var bottomMargin by mutableStateOf(0)

    var currentOrder by mutableStateOf(listOf<OrderItem>())
    var selectedTable by mutableStateOf<Table?>(null)

    // ── Pago Dividido ──────────────────────────────────────────
    var pagoDivididoActivo by mutableStateOf(false)
    var itemsDesglosados by mutableStateOf(listOf<ItemDesglosado>())
    var itemsPagados by mutableStateOf(setOf<String>())        // ids de ItemDesglosado ya cobrados
    var itemsSeleccionados by mutableStateOf(setOf<String>())  // ids seleccionados en cobro actual
    var gruposDePago by mutableStateOf(listOf<PagoGrupo>())
    var pagoDivididoCompleto by mutableStateOf(false)

    fun limpiarCola() {
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { it[PrefKeys.COLA_OFFLINE] = "[]" }
            withContext(Dispatchers.Main) { operacionesPendientes = 0 }
        }
    }

    fun iniciarPagoDividido(order: Order) {
        val desglosados = mutableListOf<ItemDesglosado>()
        order.items.forEach { item ->
            repeat(item.quantity) { idx ->
                desglosados.add(ItemDesglosado(
                    id = "${item.id}_$idx",
                    orderItemId = item.id,
                    name = item.name,
                    unitPrice = item.unitPrice,
                    notes = item.notes
                ))
            }
        }
        itemsDesglosados = desglosados
        itemsPagados = emptySet()
        itemsSeleccionados = emptySet()
        gruposDePago = emptyList()
        pagoDivididoCompleto = false
        pagoDivididoActivo = true
    }

    fun toggleItemSeleccionado(id: String) {
        itemsSeleccionados = if (id in itemsSeleccionados)
            itemsSeleccionados - id else itemsSeleccionados + id
    }

    fun cobrarSeleccion() {
        if (itemsSeleccionados.isEmpty()) return
        val itemsCobrados = itemsDesglosados.filter { it.id in itemsSeleccionados }
        val subtotal = itemsCobrados.sumOf { it.unitPrice }
        val propina = subtotal * 0.10
        val grupo = PagoGrupo(
            numero = gruposDePago.size + 1,
            items = itemsCobrados,
            subtotal = subtotal,
            propina = propina,
            total = subtotal + propina
        )
        gruposDePago = gruposDePago + grupo
        itemsPagados = itemsPagados + itemsSeleccionados
        itemsSeleccionados = emptySet()
        pagoDivididoCompleto = itemsPagados.size == itemsDesglosados.size
    }

    fun reassignTable(orderId: Int, tableId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.reassignTable(orderId, tableId)
                .onSuccess { loadOrders() }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        errorMessage = "Error al reasignar mesa: ${error.message}"
                    }
                }
        }
    }

    fun cerrarPagoDividido() {
        pagoDivididoActivo = false
        itemsDesglosados = emptyList()
        itemsPagados = emptySet()
        itemsSeleccionados = emptySet()
        gruposDePago = emptyList()
        pagoDivididoCompleto = false
    }

    init {
        loadInitialData()
        loadPersistedPrefs()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                loadOrders()
                if (operacionesPendientes > 0) {
                    procesarCola()
                }
            }
        }
    }

    private fun loadPersistedPrefs() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()

            // Cargar caché local como fallback
            prefs[PrefKeys.PLATOS_DEL_DIA]?.let { raw ->
                val lista = raw.split("|").map { it.trim() }.filter { it.isNotBlank() }
                withContext(Dispatchers.Main) { platosDelDia = lista }
            }
            prefs[PrefKeys.GUARNICIONES_DEL_DIA]?.let { raw ->
                val lista = raw.split("|").map { it.trim() }.filter { it.isNotBlank() }
                withContext(Dispatchers.Main) { guarnicionesDelDia = lista }
            }

            // Intentar cargar desde servidor (sobreescribe el caché si hay conexión)
            apiClient.getConfigDia()
                .onSuccess { config ->
                    withContext(Dispatchers.Main) {
                        platosDelDia    = config.platos
                        guarnicionesDelDia = config.guarniciones
                        if (config.restaurantName.isNotEmpty()) restaurantName = config.restaurantName
                        if (config.footerText.isNotEmpty()) footerText = config.footerText
                        topMargin       = config.topMargin
                        bottomMargin    = config.bottomMargin
                        if (config.claveSeguridad.isNotEmpty()) claveSeguridad = config.claveSeguridad
                        if (config.menuExtra.isNotEmpty()) menuExtraDelDia = config.menuExtra
                    }
                    dataStore.edit {
                        it[PrefKeys.PLATOS_DEL_DIA]      = config.platos.joinToString("|")
                        it[PrefKeys.GUARNICIONES_DEL_DIA] = config.guarniciones.joinToString("|")
                        it[PrefKeys.RESTAURANT_NAME]      = config.restaurantName
                        it[PrefKeys.FOOTER_TEXT]          = config.footerText
                        it[PrefKeys.TOP_MARGIN]           = config.topMargin.toString()
                        it[PrefKeys.BOTTOM_MARGIN]        = config.bottomMargin.toString()
                    }
                }
        }
    }

    fun startConfigStream(baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("$baseUrl/config/stream")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 0 // sin timeout — conexión persistente
                val reader = connection.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val data = line ?: continue
                    if (!data.startsWith("data:")) continue
                    val json = JSONObject(data.removePrefix("data:").trim())
                    val platos = json.getJSONArray("platos_del_dia").let { arr ->
                        List(arr.length()) { arr.getString(it) }
                    }
                    val guarniciones = json.getJSONArray("guarniciones_del_dia").let { arr ->
                        List(arr.length()) { arr.getString(it) }
                    }
                    val rnombre = json.optString("restaurant_name", "")
                    val rfooter = json.optString("footer_text", "")
                    val rtop    = json.optInt("top_margin", -1)
                    val rbottom = json.optInt("bottom_margin", -1)
                    val rclave  = json.optString("clave_seguridad", "")
                    val rmenuExtra = json.optString("menu_extra", "")
                    withContext(Dispatchers.Main) {
                        platosDelDia       = platos
                        guarnicionesDelDia = guarniciones
                        if (rnombre.isNotEmpty()) restaurantName = rnombre
                        if (rfooter.isNotEmpty()) footerText     = rfooter
                        if (rtop >= 0)    topMargin    = rtop
                        if (rbottom >= 0) bottomMargin = rbottom
                        if (rclave.isNotEmpty()) claveSeguridad  = rclave
                        if (rmenuExtra.isNotEmpty()) menuExtraDelDia = rmenuExtra
                    }
                    dataStore.edit {
                        it[PrefKeys.PLATOS_DEL_DIA]       = platos.joinToString("|")
                        it[PrefKeys.GUARNICIONES_DEL_DIA] = guarniciones.joinToString("|")
                        if (rnombre.isNotEmpty()) it[PrefKeys.RESTAURANT_NAME] = rnombre
                        if (rfooter.isNotEmpty()) it[PrefKeys.FOOTER_TEXT]     = rfooter
                        if (rtop >= 0)    it[PrefKeys.TOP_MARGIN]    = rtop.toString()
                        if (rbottom >= 0) it[PrefKeys.BOTTOM_MARGIN] = rbottom.toString()
                    }
                }
            } catch (e: Exception) {
                // Si el stream se corta, reintenta en 10 segundos
                kotlinx.coroutines.delay(10_000)
                startConfigStream(baseUrl)
            }
        }
    }

    fun saveMenuExtra(texto: String) {
        menuExtraDelDia = texto
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.setConfigDia(platosDelDia, guarnicionesDelDia, menuExtra = texto)
        }
    }

    fun savePlatosDelDia(platos: List<String>) {
        platosDelDia = platos
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { it[PrefKeys.PLATOS_DEL_DIA] = platos.joinToString("|") }
            apiClient.setConfigDia(platos, guarnicionesDelDia)
        }
    }

    fun saveGuarnicionesDelDia(guarniciones: List<String>) {
        guarnicionesDelDia = guarniciones
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { it[PrefKeys.GUARNICIONES_DEL_DIA] = guarniciones.joinToString("|") }
            apiClient.setConfigDia(platosDelDia, guarniciones)
        }
    }

    fun savePrintConfig(name: String, footer: String, top: Int, bottom: Int) {
        restaurantName = name
        footerText = footer
        topMargin = top
        bottomMargin = bottom
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit {
                it[PrefKeys.RESTAURANT_NAME] = name
                it[PrefKeys.FOOTER_TEXT]     = footer
                it[PrefKeys.TOP_MARGIN]      = top.toString()
                it[PrefKeys.BOTTOM_MARGIN]   = bottom.toString()
            }
            apiClient.setConfigDia(
                platosDelDia, guarnicionesDelDia,
                restaurantName = name, footerText = footer,
                topMargin = top, bottomMargin = bottom
            )
        }
    }

    fun saveClave(clave: String) {
        claveSeguridad = clave
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.setConfigDia(
                platosDelDia, guarnicionesDelDia,
                claveSeguridad = clave
            )
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            loadMenu()
            loadTables()
            loadOrders()
        }
    }

    fun loadMenu() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; errorMessage = null }
            apiClient.getMenu()
                .onSuccess { result -> withContext(Dispatchers.Main) { menuItems = result } }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al cargar menú: ${error.message}" } }
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    fun loadTables() {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.getTables()
                .onSuccess { result -> withContext(Dispatchers.Main) { tables = result } }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al cargar mesas: ${error.message}" } }
        }
    }

    fun loadOrders() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true }
            apiClient.getOrders()
                .onSuccess { result ->
                    withContext(Dispatchers.Main) {
                        // Mantener locales solo si aún no llegaron al servidor
                        // Una orden local se considera confirmada si el servidor
                        // ya tiene una orden de la misma mesa creada recientemente
                        // Si el servidor responde exitosamente, descartar TODAS las locales
                        // La cola se encargará de recriarlas si no llegaron
                        orders = result
                    }
                }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al cargar comandas: ${error.message}" } }
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    fun addItemToCurrentOrder(menuItem: MenuItem, notes: String = "", unitPriceOverride: Double? = null) {
        val existing = currentOrder.find { it.menuItem.id == menuItem.id && it.notes == notes }
        currentOrder = if (existing != null) {
            currentOrder.map {
                if (it.menuItem.id == menuItem.id && it.notes == notes) it.copy(quantity = it.quantity + 1) else it
            }
        } else {
            currentOrder + OrderItem(menuItem, 1, notes, unitPriceOverride)
        }
    }

    fun removeItemFromCurrentOrder(menuItem: MenuItem, notes: String = "") {
        val existing = currentOrder.find { it.menuItem.id == menuItem.id && it.notes == notes }
        if (existing != null) {
            currentOrder = if (existing.quantity > 1) {
                currentOrder.map {
                    if (it.menuItem.id == menuItem.id && it.notes == notes) it.copy(quantity = it.quantity - 1) else it
                }
            } else {
                currentOrder.filter { !(it.menuItem.id == menuItem.id && it.notes == notes) }
            }
        }
    }

    fun saveOrder(table: Table) {
        if (currentOrder.isEmpty()) return
        val ordenParaEnviar = currentOrder.toList()

        val ordenLocal = Order(
            orderId     = -(System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            tableNumber = table.name,
            status      = "pending",
            total       = ordenParaEnviar.sumOf { it.quantity * it.menuItem.price },
            createdAt   = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
            items       = ordenParaEnviar.map {
                OrderItemDetail(
                    id        = 0,
                    name      = it.menuItem.name,
                    quantity  = it.quantity,
                    unitPrice = it.menuItem.price,
                    subtotal  = it.quantity * it.menuItem.price,
                    notes     = it.notes
                )
            }
        )
        viewModelScope.launch(Dispatchers.Main) {
            orders = listOf(ordenLocal) + orders
            currentOrder = listOf()
            selectedTable = null
        }

        viewModelScope.launch(Dispatchers.IO) {
            apiClient.createOrder(table.id, ordenParaEnviar)
                .onSuccess { loadOrders() }
                .onFailure {
                    val payload = JSONObject().apply {
                        put("table_id", table.id)
                        val arr = JSONArray()
                        ordenParaEnviar.forEach { item ->
                            arr.put(JSONObject().apply {
                                put("menu_item_id", item.menuItem.id)
                                put("quantity", item.quantity)
                                put("notes", item.notes)
                            })
                        }
                        put("items", arr)
                    }.toString()
                    encolarOperacion(OperacionPendiente(tipo = TipoOperacion.CREAR_COMANDA, payload = payload))
                }
        }
    }

    fun saveOrderWithNotes(table: Table, reservaNotas: String) {
        if (currentOrder.isEmpty()) return
        val ordenParaEnviar = currentOrder.mapIndexed { idx, item ->
            if (idx == 0)
                item.copy(notes = if (item.notes.isBlank()) reservaNotas else "${item.notes}##NOTA_RESERVA_HEADER##$reservaNotas")
            else
                item
        }

        val ordenLocal = Order(
            orderId     = -(System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            tableNumber = table.name,
            status      = "pending",
            total       = ordenParaEnviar.sumOf { it.quantity * it.menuItem.price },
            createdAt   = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
            items       = ordenParaEnviar.map {
                OrderItemDetail(
                    id        = 0,
                    name      = it.menuItem.name,
                    quantity  = it.quantity,
                    unitPrice = it.menuItem.price,
                    subtotal  = it.quantity * it.menuItem.price,
                    notes     = it.notes
                )
            }
        )
        viewModelScope.launch(Dispatchers.Main) {
            orders = listOf(ordenLocal) + orders
            currentOrder = listOf()
            selectedTable = null
        }

        viewModelScope.launch(Dispatchers.IO) {
            apiClient.createOrder(table.id, ordenParaEnviar)
                .onSuccess { loadOrders() }
                .onFailure {
                    val payload = JSONObject().apply {
                        put("table_id", table.id)
                        val arr = JSONArray()
                        ordenParaEnviar.forEach { item ->
                            arr.put(JSONObject().apply {
                                put("menu_item_id", item.menuItem.id)
                                put("quantity", item.quantity)
                                put("notes", item.notes)
                            })
                        }
                        put("items", arr)
                    }.toString()
                    encolarOperacion(OperacionPendiente(tipo = TipoOperacion.CREAR_COMANDA, payload = payload))
                }
        }
    }

    fun updateOrderStatus(orderId: Int, status: String) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.updateOrderStatus(orderId, status)
                .onSuccess { loadOrders() }
                .onFailure {
                    val payload = JSONObject().apply {
                        put("order_id", orderId)
                        put("status", status)
                    }.toString()
                    encolarOperacion(OperacionPendiente(tipo = TipoOperacion.ACTUALIZAR_ESTADO, payload = payload))
                }
        }
    }

    fun assignTableAndStart(orderId: Int, tableId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.assignTableAndStart(orderId, tableId)
                .onSuccess { loadOrders() }
                .onFailure {
                    val payload = JSONObject().apply {
                        put("order_id", orderId)
                        put("table_id", tableId)
                    }.toString()
                    encolarOperacion(OperacionPendiente(tipo = TipoOperacion.ASIGNAR_MESA, payload = payload))
                }
        }
    }

    fun addItemToExistingOrder(orderId: Int, menuItem: MenuItem, notes: String = "", unitPriceOverride: Double? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.addItemToOrder(orderId, menuItem.id, 1, notes, unitPriceOverride)
                .onSuccess { loadOrders() }
                .onFailure {
                    val payload = JSONObject().apply {
                        put("order_id", orderId)
                        put("menu_item_id", menuItem.id)
                        put("quantity", 1)
                        put("notes", notes)
                        unitPriceOverride?.let { put("unit_price", it) }
                    }.toString()
                    encolarOperacion(OperacionPendiente(tipo = TipoOperacion.AGREGAR_ITEM, payload = payload))
                }
        }
    }

    fun removeItemFromExistingOrder(orderId: Int, itemId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.removeItemFromOrder(orderId, itemId)
                .onSuccess { loadOrders() }
                .onFailure {
                    val payload = JSONObject().apply {
                        put("order_id", orderId)
                        put("item_id", itemId)
                    }.toString()
                    encolarOperacion(OperacionPendiente(tipo = TipoOperacion.ELIMINAR_ITEM, payload = payload))
                }
        }
    }

    // ── Cola offline ──
    var operacionesPendientes by mutableStateOf(0)

    private suspend fun encolarOperacion(op: OperacionPendiente) {
        val prefs = dataStore.data.first()
        val colaActual = prefs[PrefKeys.COLA_OFFLINE] ?: "[]"
        val arr = JSONArray(colaActual)
        arr.put(op.toJson())
        dataStore.edit { it[PrefKeys.COLA_OFFLINE] = arr.toString() }
        withContext(Dispatchers.Main) {
            operacionesPendientes = arr.length()
            programarWorkerCola(contextoApp)
        }
    }

    fun procesarCola() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val colaStr = prefs[PrefKeys.COLA_OFFLINE] ?: "[]"
            val arr = JSONArray(colaStr)
            if (arr.length() == 0) return@launch
            val procesadas = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val op = operacionPendienteFromJson(arr.getJSONObject(i))
                val p = JSONObject(op.payload)
                val resultado = when (op.tipo) {
                    TipoOperacion.ACTUALIZAR_ESTADO ->
                        apiClient.updateOrderStatus(p.getInt("order_id"), p.getString("status"))
                    TipoOperacion.ASIGNAR_MESA ->
                        apiClient.assignTableAndStart(p.getInt("order_id"), p.getInt("table_id"))
                    TipoOperacion.AGREGAR_ITEM ->
                        apiClient.addItemToOrder(
                            p.getInt("order_id"), p.getInt("menu_item_id"),
                            p.getInt("quantity"), p.getString("notes"),
                            if (p.has("unit_price")) p.getDouble("unit_price") else null
                        )
                    TipoOperacion.ELIMINAR_ITEM ->
                        apiClient.removeItemFromOrder(p.getInt("order_id"), p.getInt("item_id"))
                    TipoOperacion.CREAR_COMANDA -> {
                        val itemsArr = p.getJSONArray("items")
                        val items = List(itemsArr.length()) { j ->
                            val itemJson = itemsArr.getJSONObject(j)
                            OrderItem(
                                menuItem = menuItems.find { it.id == itemJson.getInt("menu_item_id") }
                                    ?: return@launch,
                                quantity = itemJson.getInt("quantity"),
                                notes    = itemJson.getString("notes")
                            )
                        }
                        apiClient.createOrder(p.getInt("table_id"), items)
                    }
                }
                resultado.onSuccess { procesadas.add(op.id) }
            }
            if (procesadas.isNotEmpty()) {
                // Remover las procesadas de la cola
                val nuevaArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("id") !in procesadas) nuevaArr.put(obj)
                }
                dataStore.edit { it[PrefKeys.COLA_OFFLINE] = nuevaArr.toString() }
                withContext(Dispatchers.Main) { operacionesPendientes = nuevaArr.length() }
                kotlinx.coroutines.delay(1000) // dar tiempo al servidor para procesar
                loadOrders()
            }
        }
    }

    fun guardarEnHistorial(order: Order) {
        val propina = order.total * 0.10
        val totalConPropina = order.total + propina
        val ahora = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = HistorialEntry(order = order.copy(status = "completed"), propina = propina, totalConPropina = totalConPropina, fechaGuardado = ahora)
        historial = listOf(entry) + historial
        updateOrderStatus(order.orderId, "cancelled")
    }

    fun borrarDelHistorial(entry: HistorialEntry) {
        historial = historial.filter { it != entry }
    }

    fun printOrder(order: Order) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.printOrder(order.orderId, order.status, restaurantName, footerText, topMargin, bottomMargin)
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        errorMessage = "Error al imprimir: ${error.message}"
                    }
                }
        }
    }
    fun printPagoDividido(mesaLabel: String) {
        val grupos = gruposDePago
        if (grupos.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.printPagoDividido(mesaLabel, grupos, restaurantName, footerText, topMargin, bottomMargin)
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        errorMessage = "Error al imprimir pago dividido: ${error.message}"
                    }
                }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// UTILIDADES
// ═══════════════════════════════════════════════════════════════

fun formatCLP(amount: Double): String {
    val formatted = String.format("%,.0f", amount).replace(",", ".")
    return "$$formatted"
}

val verdesHistorial = listOf(
    Color(0xFFE8F5E9), Color(0xFFC8E6C9), Color(0xFFA5D6A7), Color(0xFF81C784),
    Color(0xFF66BB6A), Color(0xFF4CAF50), Color(0xFF43A047), Color(0xFF388E3C),
    Color(0xFF2E7D32), Color(0xFF1B5E20)
)

val ITEM_OPTIONS: Map<String, List<String>> = mapOf(
    "jugo natural" to listOf("Frambuesa", "Piña", "Frutilla", "Arándanos"),
    "jugo natural tropical" to listOf("Mango", "Maracuyá", "Chirimoya"),
    "limonada" to listOf("Azúcar", "Endulzante"),
    "limonada menta/jengibre" to listOf("Azúcar", "Endulzante"),
    "Mojito frutal" to listOf("Frambuesa", "Frutilla", "Mango", "Piña"),
    "Churrasca 1 agregado" to listOf("Ave", "Palta", "Jamón", "Queso", "Mayo", "Tomate", "Huevo", "Mantequilla"),
    "Churrasca 2 agregados" to listOf("Ave", "Palta", "Jamón", "Queso", "Mayo", "Tomate", "Huevo", "Mantequilla"),
    "Crepes 1 ingrediente" to listOf("Champiñón", "Choclo", "Pollo", "Pimentón", "Jamón", "Aceitunas", "Espinaca"),
    "Crepes 2 ingredientes" to listOf("Champiñón", "Choclo", "Pollo", "Pimentón", "Jamón", "Aceitunas", "Espinaca"),
    "bebida lata" to listOf("Coca Cola", "Coca Zero", "Kem Piña", "Limón Soda", "Bilz", "Pap", "Canada Dry"),
    "Cafe Bombon" to listOf("Sin Lactosa", "Descafeinado"),
    "Cafe con leche" to listOf("Sin Lactosa", "Descafeinado"),
    "Capuccino" to listOf("Sin Lactosa", "Descafeinado"),
    "Capuccino Chantilly" to listOf("Sin Lactosa", "Descafeinado"),
    "Capuccino Chantilly doble" to listOf("Sin Lactosa", "Descafeinado"),
    "Capuccino Doble" to listOf("Sin Lactosa", "Descafeinado"),
    "Capuccino vainilla" to listOf("Sin Lactosa", "Descafeinado"),
    "Capuccino vainilla doble" to listOf("Sin Lactosa", "Descafeinado"),
    "Chocolate caliente" to listOf("Sin Lactosa", "Descafeinado"),
    "Cortado" to listOf("Sin Lactosa", "Descafeinado"),
    "Cortado frio" to listOf("Sin Lactosa", "Descafeinado"),
    "Latte" to listOf("Sin Lactosa", "Descafeinado"),
    "Leche con chocolate" to listOf("Sin Lactosa", "Descafeinado"),
    "Mokaccino" to listOf("Sin Lactosa", "Descafeinado"),
    "Mokaccino doble" to listOf("Sin Lactosa", "Descafeinado"),
    "Banana split"            to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Cafe helado"             to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Canasta del bosque"      to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Celestino manjar"        to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Celestino nutella"       to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Copa doble"              to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Copa simple"             to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Copa simple dos sabores" to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño"),
    "Copon Casa Morena"       to listOf("Vainilla", "Chocolate", "Pistacho", "Papayas a la crema", "Tres leches", "Frambuesas a la crema", "Suspiro limeño")
)
val ITEMS_CON_DULCE = setOf("jugo natural", "jugo natural tropical")
val OPCIONES_DULCE = listOf("Azúcar", "Endulzante")
// Productos que usan el diálogo de Menú completo (entrada + guarnición)
val ITEMS_CON_MENU = listOf("Menu normal", "Menu Extra", "Churrasco al plato")

// Entradas fijas siempre disponibles
val ENTRADAS_FIJAS = listOf("Crema de zapallo", "Consomé", "Ensalada")


// ═══════════════════════════════════════════════════════════════
// ZOOM TEMPORAL CON GESTO PINCH
// ═══════════════════════════════════════════════════════════════

@Composable
fun PinchToZoomLayout(content: @Composable () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    val state = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 3f)
    }
    // Resetea al soltar los dedos
    LaunchedEffect(state.isTransformInProgress) {
        if (!state.isTransformInProgress) {
            scale = 1f
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = state)
            .graphicsLayer(scaleX = scale, scaleY = scale)
    ) {
        content()
    }
}



// ═══════════════════════════════════════════════════════════════
// SPLASH SCREEN
// ═══════════════════════════════════════════════════════════════

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        onFinished()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = Icons.Default.Restaurant, contentDescription = null,
                modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Comandas Manager", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Frontend & Backend by Liebr3, powered by Raspberry Pi", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(4.dp))
            Text("2026", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// APP PRINCIPAL
// ═══════════════════════════════════════════════════════════════

@Composable
fun RestaurantApp() {
    var showSplash by remember { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen(onFinished = { showSplash = false })
        return
    }

    val API_BASE_URL = "http://192.168.1.21:5000"
    val context = LocalContext.current
    val apiClient = remember { ApiClient(API_BASE_URL) }
    val viewModel = remember { RestaurantViewModel(apiClient, context.dataStore, context.applicationContext) }
    var currentScreen by remember { mutableStateOf("menu") }
    LaunchedEffect(Unit) {
        viewModel.startConfigStream(API_BASE_URL)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(icon = { Icon(Icons.Default.Restaurant, null) }, label = { Text("Menú") },
                    selected = currentScreen == "menu", onClick = { currentScreen = "menu" })
                NavigationBarItem(
                    icon = {
                        Box {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, null)
                            if (viewModel.operacionesPendientes > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .background(
                                            color = Color(0xFFFF9800),
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                )
                            }
                        }
                    },
                    label = { Text("Comandas") },
                    selected = currentScreen == "orders",
                    onClick = { currentScreen = "orders" }
                )
                NavigationBarItem(icon = { Icon(Icons.Default.History, null) }, label = { Text("Historial") },
                    selected = currentScreen == "historial", onClick = { currentScreen = "historial" })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Config") },
                    selected = currentScreen == "config", onClick = { currentScreen = "config" })
            }
        }
    ) { paddingValues ->
        PinchToZoomLayout {
            Box(modifier = Modifier.padding(paddingValues)) {
                when (currentScreen) {
                    "menu" -> MenuScreen(viewModel)
                    "orders" -> OrdersScreen(viewModel)
                    "historial" -> HistorialScreen(viewModel)
                    "config" -> ConfigScreen(viewModel)
                }
                viewModel.errorMessage?.let { error ->
                    AlertDialog(
                        onDismissRequest = { viewModel.errorMessage = null },
                        title = { Text("Error") },
                        text = { Text(error) },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.errorMessage = null
                            }) { Text("OK") }
                        }
                    )
                }
                if (viewModel.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        } // cierra PinchToZoom layout
    }
}

// ═══════════════════════════════════════════════════════════════
// DIÁLOGO OPCIONES SIMPLES
// ═══════════════════════════════════════════════════════════════

@Composable
fun ItemOptionsDialog(
    item: MenuItem,
    options: List<String>,
    onConfirm: (selectedOptions: String) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column {
                Text("Selecciona las opciones:", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (selected.contains(option)) selected.remove(option) else selected.add(option)
                        }.padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = selected.contains(option), onCheckedChange = {
                            if (it) selected.add(option) else selected.remove(option)
                        })
                        Text(option, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected.joinToString(", ")) }) { Text("Agregar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ═══════════════════════════════════════════════════════════════
// DIÁLOGO MENÚ NORMAL / MENÚ EXTRA
// ═══════════════════════════════════════════════════════════════

@Composable
fun MenuCompletoDialog(
    item: MenuItem,
    platos: List<String>,
    guarniciones: List<String>,
    onConfirm: (notas: String, unitPriceOverride: Double?) -> Unit,
    onDismiss: () -> Unit,
    showPlatos: Boolean = true,
    papasFritasItem: MenuItem? = null
) {
    val platoSeleccionado = remember { mutableStateOf<String?>(null) }
    val entradaSeleccionada = remember { mutableStateOf<String?>(null) }
    val guarnicionSeleccionada = remember { mutableStateOf<String?>(null) }
    val papasFritasSeleccionadas = remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (showPlatos) {
                    Text("Plato:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (platos.isEmpty()) {
                    Text("No hay platos configurados para hoy.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic)
                    } else {
                        platos.forEach { plato ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    platoSeleccionado.value = plato
                                }.padding(vertical = 2.dp)
                            ) {
                                RadioButton(
                                    selected = platoSeleccionado.value == plato,
                                    onClick = { platoSeleccionado.value = plato })
                                Text(plato, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }

                if (showPlatos) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text("Entrada:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                ENTRADAS_FIJAS.forEach { entrada ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            entradaSeleccionada.value = entrada
                        }.padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = entradaSeleccionada.value == entrada,
                            onClick = { entradaSeleccionada.value = entrada })
                        Text(entrada, modifier = Modifier.padding(start = 4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text("Guarnición:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                if (guarniciones.isEmpty()) {
                    Text("No hay guarniciones configuradas para hoy.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                } else {
                    guarniciones.forEach { guarnicion ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                guarnicionSeleccionada.value = guarnicion
                            }.padding(vertical = 2.dp)
                        ) {
                            RadioButton(selected = guarnicionSeleccionada.value == guarnicion,
                                onClick = { guarnicionSeleccionada.value = guarnicion })
                            Text(guarnicion, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                // Guarnición especial: Papas fritas
                if (papasFritasItem != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            papasFritasSeleccionadas.value = !papasFritasSeleccionadas.value
                        }.padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = papasFritasSeleccionadas.value,
                            onCheckedChange = { papasFritasSeleccionadas.value = it }
                        )
                        Column {
                            Text("Papas fritas", fontWeight = FontWeight.SemiBold)
                            Text("+${formatCLP(papasFritasItem.price)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val partes = mutableListOf<String>()
                if (showPlatos) platoSeleccionado.value?.let { partes.add("Plato: $it") }
                guarnicionSeleccionada.value?.let { partes.add("Guarnición: $it") }
                entradaSeleccionada.value?.let { partes.add("Entrada: $it") }
                if (papasFritasSeleccionadas.value) partes.add("+ Papas fritas")
                val priceOverride = if (papasFritasSeleccionadas.value && papasFritasItem != null)
                    item.price + papasFritasItem.price else null
                onConfirm(partes.joinToString(" | "), priceOverride)
            }) { Text("Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ═══════════════════════════════════════════════════════════════
// DIÁLOGO RESERVA
// ═══════════════════════════════════════════════════════════════

@Composable
fun ReservaDialog(
    onConfirm: (notasReserva: String) -> Unit,
    onDismiss: () -> Unit
) {
    var hora by remember { mutableStateOf("") }
    var personas by remember { mutableStateOf("") }
    var nombre by remember { mutableStateOf("") }
    var notas by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Datos de la reserva", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = hora,
                    onValueChange = { hora = it },
                    label = { Text("Hora de reserva") },
                    placeholder = { Text("Ej: 20:30") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = personas,
                    onValueChange = { personas = it },
                    label = { Text("Cantidad de personas") },
                    placeholder = { Text("Ej: 4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre de quien reserva") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notas,
                    onValueChange = { notas = it },
                    label = { Text("Notas para cocina (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val partes = mutableListOf<String>()
                if (hora.isNotBlank()) partes.add("Hora: $hora")
                if (personas.isNotBlank()) partes.add("Personas: $personas")
                if (nombre.isNotBlank()) partes.add("Reserva: $nombre")
                val notaBase = partes.joinToString(" | ")
                val notaFinal = if (notas.isNotBlank())
                    "$notaBase##NOTA_RESERVA##$notas"
                else notaBase
                onConfirm(notaFinal)
            }) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ═══════════════════════════════════════════════════════════════
// PANTALLA MENÚ
// ═══════════════════════════════════════════════════════════════
val CATEGORIAS_ORDEN = listOf(
    "Menus Diarios" to "⭐",
    "refrescos"     to "🥤",
    "cafes"         to "☕",
    "Fajitas"       to "🌮",
    "helados"       to "🍨",
    "Pizzas"        to "🍕",
    "Dulces"        to "🍰",
    "Sandwiches"    to "🥪",
    "aperitivos"    to "🍸",
    "cervezas"      to "🍺",
    "Crepes"        to "🥞",
    "Desayuno"      to "🍳",
    "El Huerto"     to "🥗",
    "extras"        to "➕",
    "Ingrediente extra" to "🧂",
    "papas fritas"  to "🍟",
    "tablas"        to "🪵",
    "Te"            to "🍵"
)

// Items destacados por categoría — orden define prioridad visual
val ITEMS_DESTACADOS: Map<String, List<String>> = mapOf(
    "refrescos" to listOf(
        "bebida lata", "jugo natural", "jugo tropical",
        "frapuccino", "limonada menta/jengibre", "limonada"
    ),
    "cafes" to listOf(
        "Capuccino vainilla", "Capuccino Chantilly", "Americano", "Latte",
        "Capuccino", "Cortado", "Capuccino vainilla doble", "Capuccino Chantilly doble",
        "Capuccino Doble", "Cortado doble", "Mokaccino", "Mokaccino doble", "Espresso"
    ),
    "sandwiches" to listOf(
        "Hamburguesa casa morena", "Churrasco italiano", "Barros luco", "Hamburguesa brasilera",
        "Chacarero", "Barros luco", "Barros jarpa", "Churrasco tomate"
    )
)

// Paletas de degradé por categoría — del más vendido al menos vendido
val COLORES_DESTACADOS: Map<String, List<Color>> = mapOf(
    "refrescos" to listOf(
        Color(0xFF00ACC1), Color(0xFF00ACC1), Color(0xFF00838F),
        Color(0xFF00838F), Color(0xFF01579B), Color(0xFF01579B)
    ),
    "cafes" to listOf(
        Color(0xFFFFCA28), Color(0xFFFFCA28), Color(0xFFFFA000),
        Color(0xFFFFA000), Color(0xFFFF6F00), Color(0xFFFF6F00),
        Color(0xFFBF360C), Color(0xFFBF360C), Color(0xFF795548),
        Color(0xFF795548), Color(0xFF5D4037), Color(0xFF5D4037),
        Color(0xFF4E342E)
    ),
    "sandwiches" to listOf(
        Color(0xFFFF9A3CL), Color(0xFFFF9A3CL), Color(0xFFFF6B2BL),
        Color(0xFFFF6B2BL), Color(0xFFE03A1AL), Color(0xFFE03A1AL),
        Color(0xFFB51508L), Color(0xFFB51508L)
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable



fun MenuScreen(viewModel: RestaurantViewModel) {
    var showTableSelector by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var ordenColapsada by remember { mutableStateOf(false) }
    var itemWithOptions by remember { mutableStateOf<MenuItem?>(null) }
    var itemWithMenuCompleto by remember { mutableStateOf<MenuItem?>(null) }
    var tableParaReserva by remember { mutableStateOf<Table?>(null) }

    val ITEMS_PRIORITARIOS = listOf("Menu normal", "Menu Extra")

    val filteredItems = run {
        val base = if (searchQuery.isBlank()) viewModel.menuItems
        else viewModel.menuItems.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.category.contains(searchQuery, ignoreCase = true)
        }
        val prioritarios = base
            .filter { it.name in ITEMS_PRIORITARIOS }
            .sortedBy { ITEMS_PRIORITARIOS.indexOf(it.name) }
        val resto = base.filter { it.name !in ITEMS_PRIORITARIOS }
        prioritarios + resto
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        val context = LocalContext.current
        TopAppBar(
            title = { Text("Menú del Restaurante") },
            actions = {
                IconButton(onClick = {
                    val sb = StringBuilder()
                    sb.appendLine("🍽️ MENÚ DEL DÍA")
                    sb.appendLine()
                    if (viewModel.platosDelDia.isNotEmpty()) {
                        sb.appendLine("Platos:")
                        viewModel.platosDelDia.forEach { sb.appendLine("• $it") }
                        sb.appendLine()
                    }
                    sb.appendLine("Entradas:")
                    ENTRADAS_FIJAS.forEach { sb.appendLine("• $it") }
                    sb.appendLine()
                    if (viewModel.guarnicionesDelDia.isNotEmpty()) {
                        sb.appendLine("Guarnición:")
                        viewModel.guarnicionesDelDia.forEach { sb.appendLine("• $it") }
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, sb.toString().trim())
                    }
                    context.startActivity(Intent.createChooser(intent, "Compartir menú del día"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Compartir menú del día")
                }
                IconButton(onClick = { viewModel.loadMenu() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                }
            }
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text("Buscar en el menú...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                    }
                }
            },
            singleLine = true
        )

        if (viewModel.currentOrder.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Comanda actual:", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { ordenColapsada = !ordenColapsada }) {
                        Icon(
                            if (ordenColapsada) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (ordenColapsada) "Expandir" else "Colapsar"
                        )
                    }
                }
                if (!ordenColapsada) {
                    Box {
                        Column(
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {

                            viewModel.currentOrder.forEach { orderItem ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${orderItem.quantity}x ${orderItem.menuItem.name}")
                                        if (orderItem.notes.isNotEmpty()) {
                                            Text(
                                                orderItem.notes, fontSize = 12.sp,
                                                fontStyle = FontStyle.Italic,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.Top) {
                                        IconButton(
                                            modifier = Modifier.size(36.dp),
                                            onClick = {
                                                viewModel.removeItemFromCurrentOrder(
                                                    orderItem.menuItem,
                                                    orderItem.notes
                                                )
                                            }) {
                                            Icon(Icons.Default.Remove, contentDescription = "Quitar uno")
                                        }
                                        IconButton(
                                            modifier = Modifier.size(36.dp),
                                            onClick = {
                                                viewModel.addItemToCurrentOrder(
                                                    orderItem.menuItem,
                                                    orderItem.notes
                                                )
                                            }) {
                                            Icon(Icons.Default.Add, contentDescription = "Agregar uno")
                                        }
                                        IconButton(
                                            modifier = Modifier.size(36.dp),
                                            onClick = {
                                                viewModel.currentOrder = viewModel.currentOrder.filter {
                                                    !(it.menuItem.id == orderItem.menuItem.id && it.notes == orderItem.notes)
                                                }
                                            }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Eliminar",
                                                tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val total =
                                viewModel.currentOrder.sumOf { it.quantity * it.menuItem.price }
                            Text("Total: ${formatCLP(total)}", fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { showTableSelector = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Asignar a mesa")
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Gray.copy(alpha = 0.3f)
                                        )
                                    )
                                )
                        ) // cierra Box degradé
                    }
                }
            }
        }

        var categoriaSeleccionada by remember { mutableStateOf<String?>(null) }

        // Si hay búsqueda activa, mostrar resultados directamente sin grid
        if (searchQuery.isNotBlank()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredItems) { item ->
                    MenuItemCard(item) {
                        when {
                            ITEMS_CON_MENU.any { it.equals(item.name, ignoreCase = true) } -> itemWithMenuCompleto = item
                            ITEM_OPTIONS.containsKey(item.name) -> itemWithOptions = item
                            else -> viewModel.addItemToCurrentOrder(item)
                        }
                    }
                }
            }
        } else if (categoriaSeleccionada == null) {
            // ── GRID DE CATEGORÍAS ──
            val categoriasDisponibles = CATEGORIAS_ORDEN.filter { (cat, _) ->
                viewModel.menuItems.any { it.category.equals(cat, ignoreCase = true) }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(categoriasDisponibles.chunked(2)) { fila ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        fila.forEach { (cat, icono) ->
                            Card(
                                modifier = Modifier.weight(1f).height(90.dp).clickable {
                                    categoriaSeleccionada = cat
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (cat == "Menus Diarios")
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(icono, fontSize = 28.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        cat,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                        // Si la fila tiene solo 1 elemento, agregar espacio vacío
                        if (fila.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            // ── SUBMENU DE ITEMS DE LA CATEGORÍA ──
            val destacados = ITEMS_DESTACADOS[categoriaSeleccionada?.lowercase()]
                ?: ITEMS_DESTACADOS[categoriaSeleccionada]
                ?: emptyList()
            val coloresDestacados = COLORES_DESTACADOS[categoriaSeleccionada?.lowercase()]
                ?: COLORES_DESTACADOS[categoriaSeleccionada]
                ?: emptyList()

            val itemsCategoria = run {
                val todos = viewModel.menuItems.filter {
                    it.category.equals(categoriaSeleccionada, ignoreCase = true)
                }
                val enOrden = destacados.mapNotNull { nombre ->
                    todos.find { it.name.equals(nombre, ignoreCase = true) }
                }
                val resto = todos.filter { item ->
                    destacados.none { it.equals(item.name, ignoreCase = true) }
                }.sortedBy { it.name }
                enOrden + resto
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(itemsCategoria.chunked(2)) { fila ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            fila.forEach { item ->
                                val posicion = itemsCategoria.indexOf(item)
                                val esDestacado = posicion < destacados.size
                                val colorFondo = if (esDestacado && posicion < coloresDestacados.size)
                                    coloresDestacados[posicion]
                                else
                                    MaterialTheme.colorScheme.primaryContainer
                                val colorTexto = if (esDestacado) Color.White
                                else MaterialTheme.colorScheme.onPrimaryContainer

                                Card(
                                    modifier = Modifier.weight(1f).clickable {
                                        when {
                                            ITEMS_CON_MENU.any { it.equals(item.name, ignoreCase = true) } -> itemWithMenuCompleto = item
                                            ITEM_OPTIONS.containsKey(item.name) -> itemWithOptions = item
                                            else -> viewModel.addItemToCurrentOrder(item)
                                        }
                                    },
                                    colors = CardDefaults.cardColors(containerColor = colorFondo)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            item.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            color = colorTexto
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            formatCLP(item.price),
                                            fontSize = 13.sp,
                                            color = if (esDestacado) Color.White.copy(alpha = 0.9f)
                                            else MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            if (fila.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                // FAB volver — flotante abajo a la derecha
                ExtendedFloatingActionButton(
                    onClick = { categoriaSeleccionada = null },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        CATEGORIAS_ORDEN.find { it.first.equals(categoriaSeleccionada, ignoreCase = true) }
                            ?.let { "${it.second} ${it.first}" } ?: categoriaSeleccionada ?: "",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    var notasJugoPendiente by remember { mutableStateOf<Pair<MenuItem, String>?>(null) }

    itemWithOptions?.let { item ->
        ItemOptionsDialog(
            item = item,
            options = ITEM_OPTIONS[item.name] ?: emptyList(),
            onConfirm = { selectedOptions ->
                if (item.name in ITEMS_CON_DULCE) {
                    notasJugoPendiente = Pair(item, selectedOptions)
                } else {
                    viewModel.addItemToCurrentOrder(item, selectedOptions)
                }
                itemWithOptions = null
            },
            onDismiss = { itemWithOptions = null }
        )
    }

    notasJugoPendiente?.let { (item, notasPrevias) ->
        ItemOptionsDialog(
            item = item.copy(name = "¿Con azúcar o endulzante?"),
            options = OPCIONES_DULCE,
            onConfirm = { dulce ->
                val notasFinal = if (dulce.isNotEmpty()) "$notasPrevias | $dulce" else notasPrevias
                viewModel.addItemToCurrentOrder(item, notasFinal)
                notasJugoPendiente = null
            },
            onDismiss = {
                viewModel.addItemToCurrentOrder(item, notasPrevias)
                notasJugoPendiente = null
            }
        )
    }

    itemWithMenuCompleto?.let { item ->
        MenuCompletoDialog(
            item = item,
            platos = if (item.name == "Menu Extra")
                listOf(viewModel.menuExtraDelDia).filter { it.isNotEmpty() }
            else viewModel.platosDelDia,
            guarniciones = viewModel.guarnicionesDelDia,
            showPlatos = item.name != "Churrasco al plato",
            papasFritasItem = viewModel.menuItems.find { it.name == "Papas fritas guarnicion especial" },
            onConfirm = { notas, priceOverride ->
                viewModel.addItemToCurrentOrder(item, notas, priceOverride)
                itemWithMenuCompleto = null
            },
            onDismiss = { itemWithMenuCompleto = null }
        )
    }

    if (showTableSelector) {
        AlertDialog(
            onDismissRequest = { showTableSelector = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Seleccionar mesa")
                    IconButton(onClick = { viewModel.loadTables() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recargar mesas")
                    }
                }
            },
            text = {
                if (viewModel.tables.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Sin mesas disponibles. Recarga con ↻",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn {
                        items(viewModel.tables.sortedWith(compareBy { if (it.name == "Reserva") 0 else 1 })) { table ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(4.dp).clickable {
                                    if (table.name == "Reserva") {
                                        tableParaReserva = table
                                        showTableSelector = false
                                    } else {
                                        viewModel.saveOrder(table)
                                        showTableSelector = false
                                    }
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (table.name == "Reserva")
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    else
                                        MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Text(
                                    text = when (table.name) {
                                        "Para llevar" -> "Para llevar"
                                        "Reserva" -> "📋 Reserva"
                                        else -> "Mesa ${table.name}"
                                    },
                                    modifier = Modifier.padding(16.dp),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (table.name == "Reserva")
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    else
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTableSelector = false }) { Text("Cancelar") } }
        )
    }

    tableParaReserva?.let { table ->
        ReservaDialog(
            onConfirm = { notasReserva ->
                viewModel.saveOrderWithNotes(table, notasReserva)
                tableParaReserva = null
            },
            onDismiss = { tableParaReserva = null }
        )
    }
}

@Composable
fun MenuItemCard(item: MenuItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isAvailable) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (item.name in listOf("Menu normal", "Menu Extra")) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "⭐ Más pedido",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (item.description.isNotEmpty()) {
                    Text(text = item.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(text = formatCLP(item.price), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// PANTALLA COMANDAS
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(viewModel: RestaurantViewModel) {
    var selectedOrder by remember { mutableStateOf<Order?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    val selectedTerminadas =
        remember { mutableStateListOf<Int>() } // lista de orderId seleccionados
    var modoSeleccion by remember { mutableStateOf(false) }

    val activeOrders = viewModel.orders.filter {
        it.status in listOf(
            "pending",
            "in_progress"
        ) && it.tableNumber != "Reserva"
    }.sortedByDescending { it.createdAt }
    val reservaOrders = viewModel.orders.filter {
        it.tableNumber == "Reserva" && it.status in listOf(
            "pending",
            "in_progress"
        )
    }.sortedByDescending { it.createdAt }
    val terminatedOrders =
        viewModel.orders.filter { it.status == "completed" }.sortedByDescending { it.createdAt }


    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Comandas")
                    if (viewModel.operacionesPendientes > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "⏳ ${viewModel.operacionesPendientes} pendiente${if (viewModel.operacionesPendientes > 1) "s" else ""}",
                            fontSize = 12.sp,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            },
            actions = {
                if (selectedTab == 2) {
                    if (modoSeleccion && selectedTerminadas.isNotEmpty()) {
                        IconButton(onClick = {
                            terminatedOrders.filter { it.orderId in selectedTerminadas }
                                .forEach { viewModel.guardarEnHistorial(it) }
                            selectedTerminadas.clear()
                            modoSeleccion = false
                            viewModel.loadOrders()
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Guardar selección")
                        }
                    }
                    IconButton(onClick = {
                        modoSeleccion = !modoSeleccion
                        selectedTerminadas.clear()
                    }) {
                        Icon(
                            if (modoSeleccion) Icons.Default.Close else Icons.Default.CheckBox,
                            contentDescription = if (modoSeleccion) "Cancelar selección" else "Seleccionar"
                        )
                    }
                }
                IconButton(onClick = { viewModel.loadOrders() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                }
            }
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0, onClick = { selectedTab = 0; selectedOrder = null },
                text = { Text("Activas (${activeOrders.size})") })
            Tab(
                selected = selectedTab == 1, onClick = { selectedTab = 1; selectedOrder = null },
                text = { Text("Reservas (${reservaOrders.size})") })
            Tab(
                selected = selectedTab == 2, onClick = { selectedTab = 2; selectedOrder = null },
                text = { Text("Terminadas (${terminatedOrders.size})") })
        }

        val ordersToShow = when (selectedTab) {
            0 -> activeOrders
            1 -> reservaOrders
            else -> terminatedOrders
        }

        if (selectedOrder == null) {
            if (ordersToShow.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when (selectedTab) {
                            0 -> "No hay comandas activas"
                            1 -> "No hay reservas activas"
                            else -> "No hay comandas terminadas hoy"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ordersToShow) { order ->
                        if (selectedTab == 2 && modoSeleccion) {
                            val isSelected = order.orderId in selectedTerminadas
                            OrderCard(
                                order = order,
                                showPrice = true,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) selectedTerminadas.remove(order.orderId)
                                    else selectedTerminadas.add(order.orderId)
                                }
                            )
                        } else {
                            OrderCard(order, showPrice = selectedTab == 2) { selectedOrder = order }
                        }
                    }
                }
            }
        } else {
            OrderDetail(
                order = selectedOrder!!,
                viewModel = viewModel,
                isTerminada = selectedTab == 2,
                onBack = { selectedOrder = null }
            )
        }
    }
}

@Composable
fun OrderCard(order: Order, showPrice: Boolean = false, isSelected: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                order.status == "pending" -> MaterialTheme.colorScheme.errorContainer
                order.status == "in_progress" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Mesa ${order.tableNumber}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(text = order.items.joinToString(", ") { "${it.quantity}x ${it.name}" }, fontSize = 16.sp)
                Text(text = order.createdAt, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
            } else if (showPrice) {
                Text(text = formatCLP(order.total), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DETALLE DE COMANDA
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetail(order: Order, viewModel: RestaurantViewModel, isTerminada: Boolean, onBack: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var modoEdicion by remember { mutableStateOf(false) }
    var itemWithOptions by remember { mutableStateOf<MenuItem?>(null) }
    var itemWithMenuCompleto by remember { mutableStateOf<MenuItem?>(null) }
    var tabPagos by remember { mutableStateOf(0) }
    var itemEditando by remember { mutableStateOf<OrderItemDetail?>(null) }
    var showReasignarMesa by remember { mutableStateOf(false) }

    val currentOrder = viewModel.orders.find { it.orderId == order.orderId } ?: order
    val propina = currentOrder.total * 0.10
    val totalConPropina = currentOrder.total + propina

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Text("Orden #${currentOrder.orderId}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            // Botón editar disponible en todos los estados
            IconButton(onClick = { modoEdicion = !modoEdicion }) {
                Icon(
                    if (modoEdicion) Icons.Default.Check else Icons.Default.Edit,
                    contentDescription = if (modoEdicion) "Finalizar edición" else "Editar comanda",
                    tint = if (modoEdicion) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (modoEdicion) {
            // ── MODO EDICIÓN ──
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Text("Modo edición activo — toca ✓ para finalizar", modifier = Modifier.padding(8.dp),
                    fontSize = 13.sp, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }

            // Items actuales con altura máxima y scroll propio
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(max = 200.dp)) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Items actuales:", fontWeight = FontWeight.Bold)
                        if (currentOrder.items.size > 3)
                            Text("↕ desliza", fontSize = 11.sp, fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    currentOrder.items.forEach { item ->
                        val menuItemRef = viewModel.menuItems.find { it.name.equals(item.name, ignoreCase = true) }
                        val tieneOpciones = menuItemRef != null &&
                                (ITEMS_CON_MENU.any { it.equals(item.name, ignoreCase = true) } ||
                                        ITEM_OPTIONS.containsKey(item.name))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${item.quantity}x ${item.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                if (item.notes.isNotEmpty()) {
                                    Text(item.notes, fontSize = 12.sp, fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (tieneOpciones) {
                                IconButton(onClick = {
                                    itemEditando = item
                                    if (ITEMS_CON_MENU.any { it.equals(item.name, ignoreCase = true) })
                                        itemWithMenuCompleto = menuItemRef
                                    else
                                        itemWithOptions = menuItemRef
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Editar item",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = { viewModel.removeItemFromExistingOrder(currentOrder.orderId, item.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Eliminar",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            var searchQuery by remember { mutableStateOf("") }
            val filteredItems = if (searchQuery.isBlank()) viewModel.menuItems
            else viewModel.menuItems.filter { it.name.contains(searchQuery, ignoreCase = true) }

            // Buscador fijo
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Buscar item para agregar...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true
            )

            // Lista del menú ocupa el espacio restante
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredItems) { menuItem ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            when {
                                ITEMS_CON_MENU.any { it.equals(menuItem.name, ignoreCase = true) } -> {
                                    itemWithMenuCompleto = menuItem
                                }
                                ITEM_OPTIONS.containsKey(menuItem.name) -> {
                                    itemWithOptions = menuItem
                                }
                                else -> viewModel.addItemToExistingOrder(currentOrder.orderId, menuItem)
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(menuItem.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Add, contentDescription = "Agregar",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

        } else {
            // ── MODO VISTA NORMAL ──
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mesa ${currentOrder.tableNumber}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showReasignarMesa = true }) {
                            Icon(Icons.Default.TableRestaurant, contentDescription = "Cambiar mesa",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Estado: ${when(currentOrder.status) {
                        "pending" -> "Pendiente"
                        "in_progress" -> "En preparación"
                        "completed" -> "Terminada"
                        "cancelled" -> "Cancelada"
                        else -> currentOrder.status
                    }}")
                    Text("Hora: ${currentOrder.createdAt}", fontSize = 14.sp)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    currentOrder.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${item.quantity}x ${item.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                if (item.notes.isNotEmpty() && !isTerminada) {
                                    Text(text = item.notes, fontSize = 13.sp, fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isTerminada) {
                                Text(formatCLP(item.subtotal), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    if (isTerminada) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal:", fontSize = 16.sp)
                            Text(formatCLP(currentOrder.total), fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Propina (10%):", fontSize = 16.sp)
                            Text(formatCLP(propina), fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TOTAL:", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(formatCLP(totalConPropina), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (currentOrder.status == "pending") {
                        var showAsignarMesa by remember { mutableStateOf(false) }
                        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)){
                            Button(onClick = {
                                if (currentOrder.tableNumber == "Reserva") {
                                    showAsignarMesa = true
                                } else {
                                    viewModel.updateOrderStatus(currentOrder.orderId, "in_progress")
                                    viewModel.loadOrders()
                                }
                            }, modifier = Modifier.weight(1f)) { Text("Iniciar") }
                            OutlinedButton(onClick = { viewModel.printOrder(currentOrder) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Imprimir")
                            }
                        }
                        if (showAsignarMesa) {
                            AlertDialog(
                                onDismissRequest = { showAsignarMesa = false },
                                title = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Asignar mesa")
                                        IconButton(onClick = { viewModel.loadTables() }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                                        }
                                    }
                                },
                                text = {
                                    LazyColumn {
                                        items(viewModel.tables.filter { it.name != "Reserva" }) { table ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(4.dp).clickable {
                                                    viewModel.assignTableAndStart(currentOrder.orderId, table.id)
                                                    showAsignarMesa = false
                                                    onBack()
                                                },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                                )
                                            ) {
                                                Text(
                                                    text = if (table.name == "Para llevar") "Para llevar" else "Mesa ${table.name}",
                                                    modifier = Modifier.padding(16.dp),
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                },
                                confirmButton = { TextButton(onClick = { showAsignarMesa = false }) { Text("Cancelar") } }
                            )
                        }
                    }

                    if (currentOrder.status == "in_progress") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                viewModel.updateOrderStatus(currentOrder.orderId, "completed")
                                viewModel.loadOrders()
                                onBack()
                            }, modifier = Modifier.weight(1f)) { Text("Terminar") }
                            OutlinedButton(onClick = { viewModel.printOrder(currentOrder) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Imprimir")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.iniciarPagoDividido(currentOrder)
                                tabPagos = 0
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CallSplit, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pago dividido")
                        }
                    }

                    if (currentOrder.status == "completed") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.printOrder(currentOrder) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Imprimir")
                            }
                            Button(onClick = {
                                viewModel.guardarEnHistorial(currentOrder)
                                onBack()
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Guardar")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                viewModel.iniciarPagoDividido(currentOrder)
                                tabPagos = 0
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CallSplit, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pago dividido")
                        }
                    }


                    if (showReasignarMesa) {
                        AlertDialog(
                            onDismissRequest = { showReasignarMesa = false },
                            title = { Text("Cambiar mesa") },
                            text = {
                                LazyColumn {
                                    items(viewModel.tables) { table ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(4.dp)
                                                .clickable {
                                                    viewModel.reassignTable(currentOrder.orderId, table.id)
                                                    showReasignarMesa = false
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        ) {
                                            Text(
                                                text = when (table.name) {
                                                    "Para llevar" -> "Para llevar"
                                                    "Reserva" -> "📋 Reserva"
                                                    else -> "Mesa ${table.name}"
                                                },
                                                modifier = Modifier.padding(16.dp),
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showReasignarMesa = false }) { Text("Cancelar") }
                            }
                        )
                    }
                }
            }
        }
        }

        // Diálogos en modo edición
        var notasJugoPendiente by remember { mutableStateOf<Pair<MenuItem, String>?>(null) }

        itemWithOptions?.let { item ->
            ItemOptionsDialog(
                item = item,
                options = ITEM_OPTIONS[item.name] ?: emptyList(),
                onConfirm = { selectedOptions ->
                    if (item.name in ITEMS_CON_DULCE) {
                        notasJugoPendiente = Pair(item, selectedOptions)
                    } else {
                        itemEditando?.let { editando ->
                            viewModel.removeItemFromExistingOrder(currentOrder.orderId, editando.id)
                            itemEditando = null
                        }
                        viewModel.addItemToExistingOrder(currentOrder.orderId, item, selectedOptions)
                    }
                    itemWithOptions = null
                },
                onDismiss = { itemWithOptions = null; itemEditando = null }
            )
        }

        notasJugoPendiente?.let { (item, notasPrevias) ->
            ItemOptionsDialog(
                item = item.copy(name = "¿Con azúcar o endulzante?"),
                options = OPCIONES_DULCE,
                onConfirm = { dulce ->
                    val notasFinal = if (dulce.isNotEmpty()) "$notasPrevias | $dulce" else notasPrevias
                    viewModel.addItemToExistingOrder(currentOrder.orderId, item, notasFinal)
                    notasJugoPendiente = null
                },
                onDismiss = {
                    viewModel.addItemToExistingOrder(currentOrder.orderId, item, notasPrevias)
                    notasJugoPendiente = null
                }
            )
        }

        itemWithMenuCompleto?.let { item ->
            MenuCompletoDialog(
                item = item,
                platos = if (item.name == "Menu Extra")
                    listOf(viewModel.menuExtraDelDia).filter { it.isNotEmpty() }
                else viewModel.platosDelDia,
                guarniciones = viewModel.guarnicionesDelDia,
                showPlatos = item.name != "Churrasco al plato",
                papasFritasItem = viewModel.menuItems.find { it.name == "Papas fritas guarnicion especial" },
                onConfirm = { notas, priceOverride ->
                    itemEditando?.let { editando ->
                        viewModel.removeItemFromExistingOrder(currentOrder.orderId, editando.id)
                        itemEditando = null
                    }
                    viewModel.addItemToExistingOrder(currentOrder.orderId, item, notas, priceOverride)
                    itemWithMenuCompleto = null
                },
                onDismiss = { itemWithMenuCompleto = null; itemEditando = null }
            )
        }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Eliminar comanda") },
            text = { Text("¿Confirmas que deseas eliminar la orden #${currentOrder.orderId} de la mesa ${currentOrder.tableNumber}?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateOrderStatus(currentOrder.orderId, "cancelled")
                    showDeleteConfirm = false
                    onBack()
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Eliminar")
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } }
        )
    }

        // Pantalla de Pago Dividido superpuesta dentro del Box
        if (viewModel.pagoDivididoActivo) {
            PagoDivididoScreen(
                viewModel = viewModel,
                mesaLabel = currentOrder.tableNumber,
                onCerrar = { viewModel.cerrarPagoDividido() }
            )
        }
    } // cierre Box
} // cierre fun OrderDetail

// ═══════════════════════════════════════════════════════════════
// PANTALLA PAGO DIVIDIDO
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagoDivididoScreen(
    viewModel: RestaurantViewModel,
    mesaLabel: String,
    onCerrar: () -> Unit
) {
    var tabSeleccionada by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TopAppBar
        TopAppBar(
            title = { Text("Pago dividido — Mesa $mesaLabel") },
            navigationIcon = {
                IconButton(onClick = onCerrar) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cerrar")
                }
            }
        )

        // Pestañas
        TabRow(selectedTabIndex = tabSeleccionada) {
            Tab(selected = tabSeleccionada == 0, onClick = { tabSeleccionada = 0 },
                text = { Text("Detalle") })
            Tab(selected = tabSeleccionada == 1, onClick = { tabSeleccionada = 1 },
                text = { Text("Pagos (${viewModel.gruposDePago.size})") })
        }

        when (tabSeleccionada) {
            0 -> {
                // ── Pestaña Detalle ──
                Column(modifier = Modifier.fillMaxSize()) {

                    // Botón Cobrar selección
                    Button(
                        onClick = {
                            viewModel.cobrarSeleccion()
                            if (viewModel.pagoDivididoCompleto) tabSeleccionada = 1
                        },
                        enabled = viewModel.itemsSeleccionados.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.AttachMoney, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cobrar selección (${viewModel.itemsSeleccionados.size} items)")
                    }

                    // Aviso pago completado
                    if (viewModel.pagoDivididoCompleto) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("✓ Pago completado — todos los items cobrados",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }

                    // Lista de items desglosados
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(viewModel.itemsDesglosados) { item ->
                            val pagado = item.id in viewModel.itemsPagados
                            val seleccionado = item.id in viewModel.itemsSeleccionados
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        pagado -> MaterialTheme.colorScheme.surfaceVariant
                                        seleccionado -> MaterialTheme.colorScheme.secondaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (pagado)
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                        if (item.notes.isNotEmpty()) {
                                            Text(
                                                text = item.notes,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = formatCLP(item.unitPrice),
                                            fontSize = 13.sp,
                                            color = if (pagado)
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else
                                                MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Checkbox(
                                        checked = seleccionado,
                                        onCheckedChange = {
                                            if (!pagado) viewModel.toggleItemSeleccionado(item.id)
                                        },
                                        enabled = !pagado
                                    )
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // ── Pestaña Pagos ──
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (viewModel.gruposDePago.isEmpty()) {
                        item {
                            Text(
                                "Aún no hay pagos registrados.",
                                modifier = Modifier.padding(top = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    } else {
                        items(viewModel.gruposDePago) { grupo ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Pago ${grupo.numero}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    grupo.items.forEach { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(item.name, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                            Text(formatCLP(item.unitPrice), fontSize = 14.sp)
                                        }
                                        if (item.notes.isNotEmpty()) {
                                            Text(
                                                item.notes,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Subtotal:", fontSize = 13.sp)
                                        Text(formatCLP(grupo.subtotal), fontSize = 13.sp)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Propina (10%):", fontSize = 13.sp)
                                        Text(formatCLP(grupo.propina), fontSize = 13.sp)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Total:", fontWeight = FontWeight.Bold)
                                        Text(formatCLP(grupo.total), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.printPagoDividido(mesaLabel) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Imprimir resumen")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════
// PANTALLA HISTORIAL
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialScreen(viewModel: RestaurantViewModel) {
    var selectedEntry by remember { mutableStateOf<HistorialEntry?>(null) }
    val selectedEntries = remember { mutableStateListOf<HistorialEntry>() }
    var modoSeleccion by remember { mutableStateOf(false) }
    var showClaveDialog by remember { mutableStateOf(false) }
    var claveIngresada by remember { mutableStateOf("") }
    var claveError by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Historial del día") },
            actions = {
                if (modoSeleccion && selectedEntries.isNotEmpty()) {
                    IconButton(onClick = {
                        showClaveDialog = true
                        claveIngresada = ""
                        claveError = false
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Borrar selección",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = {
                    modoSeleccion = !modoSeleccion
                    selectedEntries.clear()
                }) {
                    Icon(
                        if (modoSeleccion) Icons.Default.Close else Icons.Default.CheckBox,
                        contentDescription = if (modoSeleccion) "Cancelar selección" else "Seleccionar"
                    )
                }
            }
        )

        if (showClaveDialog) {
            AlertDialog(
                onDismissRequest = { showClaveDialog = false },
                title = { Text("Borrar selección") },
                text = {
                    Column {
                        Text("¿Borrar ${selectedEntries.size} entrada(s) del historial?")
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Ingresa la clave de seguridad:", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = claveIngresada,
                            onValueChange = { claveIngresada = it; claveError = false },
                            label = { Text("Clave") },
                            singleLine = true,
                            isError = claveError,
                            supportingText = if (claveError) {{ Text("Clave incorrecta", color = MaterialTheme.colorScheme.error) }} else null
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (claveIngresada == viewModel.claveSeguridad) {
                                selectedEntries.forEach { viewModel.borrarDelHistorial(it) }
                                selectedEntries.clear()
                                modoSeleccion = false
                                showClaveDialog = false
                            } else { claveError = true }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Borrar") }
                },
                dismissButton = { TextButton(onClick = { showClaveDialog = false }) { Text("Cancelar") } }
            )
        }

        if (selectedEntry == null) {
            if (viewModel.historial.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay comandas guardadas hoy", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sorted = viewModel.historial.sortedByDescending { it.order.createdAt }
                    items(sorted.size) { index ->
                        val entry = sorted[index]
                        val colorFondo = if (entry in selectedEntries)
                            MaterialTheme.colorScheme.primaryContainer
                        else verdesHistorial[index % verdesHistorial.size]
                        HistorialCard(
                            entry = entry,
                            colorFondo = colorFondo,
                            isSelected = entry in selectedEntries,
                            onClick = {
                                if (modoSeleccion) {
                                    if (entry in selectedEntries) selectedEntries.remove(entry)
                                    else selectedEntries.add(entry)
                                } else {
                                    selectedEntry = entry
                                }
                            }
                        )
                    }
                }
            }
        } else {
            HistorialDetail(entry = selectedEntry!!, viewModel = viewModel, onBack = { selectedEntry = null })
        }
    }
}

@Composable
fun HistorialCard(entry: HistorialEntry, colorFondo: Color, isSelected: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colorFondo)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.fechaGuardado, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = "Mesa ${entry.order.tableNumber}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(text = entry.order.items.joinToString(", ") { "${it.quantity}x ${it.name}" }, fontSize = 14.sp)
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
            } else {
                Text(text = formatCLP(entry.totalConPropina), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HistorialDetail(entry: HistorialEntry, viewModel: RestaurantViewModel, onBack: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var claveIngresada by remember { mutableStateOf("") }
    var claveError by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
            Text("Orden #${entry.order.orderId}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = entry.fechaGuardado, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Mesa ${entry.order.tableNumber}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Estado: Guardada", fontSize = 14.sp)

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                entry.order.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.quantity}x ${item.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(formatCLP(item.subtotal), fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Subtotal:", fontSize = 16.sp)
                    Text(formatCLP(entry.order.total), fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Propina (10%):", fontSize = 16.sp)
                    Text(formatCLP(entry.propina), fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TOTAL:", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(formatCLP(entry.totalConPropina), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.printOrder(entry.order) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Print, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Imprimir")
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true; claveIngresada = ""; claveError = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Borrar")
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Borrar del historial") },
            text = {
                Column {
                    Text("¿Deseas borrar la orden #${entry.order.orderId} de la mesa ${entry.order.tableNumber}?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Ingresa la clave de seguridad para confirmar:", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = claveIngresada,
                        onValueChange = { claveIngresada = it; claveError = false },
                        label = { Text("Clave") },
                        singleLine = true,
                        isError = claveError,
                        supportingText = if (claveError) {{ Text("Clave incorrecta", color = MaterialTheme.colorScheme.error) }} else null
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (claveIngresada == viewModel.claveSeguridad) {
                            viewModel.borrarDelHistorial(entry)
                            showDeleteConfirm = false
                            onBack()
                        } else { claveError = true }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// PANTALLA CONFIGURACIÓN
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: RestaurantViewModel) {
    var claveInput by remember { mutableStateOf(viewModel.claveSeguridad) }
    var claveSaved by remember { mutableStateOf(false) }
    var guarnicionesInput by remember { mutableStateOf(viewModel.guarnicionesDelDia.joinToString(", ")) }
    var guarnicionesSaved by remember { mutableStateOf(false) }
    var platosInput by remember { mutableStateOf(viewModel.platosDelDia.joinToString(", ")) }
    var platosSaved by remember { mutableStateOf(false)}

        LaunchedEffect(viewModel.platosDelDia) {
            if (!platosSaved) platosInput = viewModel.platosDelDia.joinToString(", ")
        }
        LaunchedEffect(viewModel.guarnicionesDelDia) {
            if (!guarnicionesSaved) guarnicionesInput = viewModel.guarnicionesDelDia.joinToString(", ")
        }


    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { TopAppBar(title = { Text("Configuración") }) }

        item {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Servidor API", fontWeight = FontWeight.Bold)
                    Text("Configura la IP del servidor en el código")
                    Text("Ubicación: RestaurantApp() -> API_BASE_URL", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadMenu() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Recargar Menú")
                    }
                    Button(onClick = { viewModel.loadOrders() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Recargar Comandas")
                    }
                }
            }
        }

        item {
            var printSaved by remember { mutableStateOf(false) }
            var nameInput by remember { mutableStateOf(viewModel.restaurantName) }
            var footerInput by remember { mutableStateOf(viewModel.footerText) }
            var topInput by remember { mutableStateOf(viewModel.topMargin.toString()) }
            var bottomInput by remember { mutableStateOf(viewModel.bottomMargin.toString()) }

            LaunchedEffect(viewModel.restaurantName) { if (!printSaved) nameInput = viewModel.restaurantName }
            LaunchedEffect(viewModel.footerText) { if (!printSaved) footerInput = viewModel.footerText }
            LaunchedEffect(viewModel.topMargin) { if (!printSaved) topInput = viewModel.topMargin.toString() }
            LaunchedEffect(viewModel.bottomMargin) { if (!printSaved) bottomInput = viewModel.bottomMargin.toString() }

            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Impresión", fontWeight = FontWeight.Bold)
                    Text(
                        "Texto que aparece en el encabezado y pie de cada ticket.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it; printSaved = false },
                        label = { Text("Nombre del restaurante") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = footerInput,
                        onValueChange = { footerInput = it; printSaved = false },
                        label = { Text("Mensaje de pie de ticket") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = topInput,
                            onValueChange = { topInput = it.filter { c -> c.isDigit() }; printSaved = false },
                            label = { Text("Margen superior (líneas)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bottomInput,
                            onValueChange = { bottomInput = it.filter { c -> c.isDigit() }; printSaved = false },
                            label = { Text("Margen inferior (líneas)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.savePrintConfig(
                                nameInput.trim().ifBlank { "Mi Restaurante" },
                                footerInput.trim().ifBlank { "Gracias por su visita" },
                                topInput.toIntOrNull() ?: 0,
                                bottomInput.toIntOrNull() ?: 0
                            )
                            printSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (printSaved) "✓ Configuración guardada" else "Guardar configuración de impresión")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Platos del día", fontWeight = FontWeight.Bold)
                    Text("Ingresa hasta 4 platos separados por coma.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = platosInput,
                        onValueChange = { platosInput = it; platosSaved = false },
                        label = { Text("Platos") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val platos = platosInput
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .take(4)
                            viewModel.savePlatosDelDia(platos)
                            platosSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (platosSaved) "✓ Platos guardados" else "Guardar platos")
                    }
                    if (viewModel.platosDelDia.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Activos hoy:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        viewModel.platosDelDia.forEach { p ->
                            Text("• $p", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        item {
            var menuExtraInput by remember { mutableStateOf(viewModel.menuExtraDelDia) }
            var menuExtraSaved by remember { mutableStateOf(false) }
            LaunchedEffect(viewModel.menuExtraDelDia) {
                if (!menuExtraSaved) menuExtraInput = viewModel.menuExtraDelDia
            }
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Menú extra", fontWeight = FontWeight.Bold)
                    Text("Nombre del plato de fondo del Menú Extra de hoy.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = menuExtraInput,
                        onValueChange = { menuExtraInput = it; menuExtraSaved = false },
                        label = { Text("Plato de fondo Menú Extra") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.saveMenuExtra(menuExtraInput.trim())
                            menuExtraSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (menuExtraSaved) "✓ Menú extra guardado" else "Guardar Menú extra")
                    }
                    if (viewModel.menuExtraDelDia.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Hoy: ${viewModel.menuExtraDelDia}", fontSize = 13.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Guarniciones del día", fontWeight = FontWeight.Bold)
                    Text("Ingresa las guarniciones separadas por coma.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Ejemplo: Puré de papas, Arroz, Papas fritas", fontSize = 12.sp,
                        fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = guarnicionesInput,
                        onValueChange = { guarnicionesInput = it; guarnicionesSaved = false },
                        label = { Text("Guarniciones") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val guarniciones = guarnicionesInput
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            viewModel.saveGuarnicionesDelDia(guarniciones)
                            guarnicionesSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (guarnicionesSaved) "✓ Guarniciones guardadas" else "Guardar guarniciones")
                    }
                    if (viewModel.guarnicionesDelDia.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Activas hoy:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        viewModel.guarnicionesDelDia.forEach { g ->
                            Text("• $g", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Clave de seguridad", fontWeight = FontWeight.Bold)
                    Text("Se usa para borrar entradas del historial", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = claveInput,
                        onValueChange = { claveInput = it; claveSaved = false },
                        label = { Text("Nueva clave") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.saveClave(claveInput); claveSaved = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (claveSaved) "✓ Clave guardada" else "Guardar clave")
                    }
                }
            }
        }
        item {
            val context = LocalContext.current
            val diasSemana = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
            var platosInput by remember { mutableStateOf(viewModel.platosGenerador.joinToString(", ")) }
            var platosSaved by remember { mutableStateOf(false) }
            val diasSeleccionados = remember { mutableStateListOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes") }
            var menuGenerado by remember { mutableStateOf("") }

            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Generador de menú semanal", fontWeight = FontWeight.Bold)
                    Text("Ingresa los platos disponibles separados por coma.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = platosInput,
                        onValueChange = { platosInput = it; platosSaved = false },
                        label = { Text("Platos disponibles") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            viewModel.platosGenerador = platosInput
                                .split(",").map { it.trim() }.filter { it.isNotBlank() }
                            platosSaved = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (platosSaved) "✓ Platos guardados" else "Guardar platos")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Días de la semana:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    diasSemana.forEach { dia ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (dia in diasSeleccionados) diasSeleccionados.remove(dia)
                                else diasSeleccionados.add(diasSemana.filter { it in diasSeleccionados || it == dia }.first().let { dia })
                            }
                        ) {
                            Checkbox(
                                checked = dia in diasSeleccionados,
                                onCheckedChange = {
                                    if (it) diasSeleccionados.add(dia) else diasSeleccionados.remove(dia)
                                }
                            )
                            Text(dia, fontSize = 14.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val diasOrdenados = diasSemana.filter { it in diasSeleccionados }
                            menuGenerado = generarMenuSemanal(viewModel.platosGenerador, diasOrdenados)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.platosGenerador.size >= diasSeleccionados.size * 2
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generar menú")
                    }

                    if (menuGenerado.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = menuGenerado,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val diasOrdenados = diasSemana.filter { it in diasSeleccionados }
                                    menuGenerado = generarMenuSemanal(viewModel.platosGenerador, diasOrdenados)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Nueva lista")
                            }
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, menuGenerado)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Compartir menú semanal"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Compartir")
                            }
                        }
                    }
                    if (viewModel.platosGenerador.size < diasSeleccionados.size * 2) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Necesitas al menos ${diasSeleccionados.size * 2} platos para ${diasSeleccionados.size} días.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Estado del sistema", fontWeight = FontWeight.Bold)
                    Text("Items en menú: ${viewModel.menuItems.size}")
                    Text("Mesas: ${viewModel.tables.size}")
                    Text("Comandas activas: ${viewModel.orders.filter { it.status in listOf("pending","in_progress") }.size}")
                    Text("Entradas en historial: ${viewModel.historial.size}")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.limpiarCola() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Limpiar cola offline (${viewModel.operacionesPendientes} pendientes)")
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// GENERADOR DE MENÚ SEMANAL
// ═══════════════════════════════════════════════════════════════

// Palabras irrelevantes que no se usan para comparar similitud
val STOP_WORDS = setOf("con", "de", "del", "al", "la", "el", "y", "en", "a", "sin", "salsa")

fun palabrasSignificativas(plato: String): Set<String> =
    plato.lowercase().split(" ", "/").map { it.trim() }
        .filter { it.length > 2 && it !in STOP_WORDS }.toSet()

fun sonSimilares(plato1: String, plato2: String): Boolean {
    val p1 = palabrasSignificativas(plato1)
    val p2 = palabrasSignificativas(plato2)
    return p1.intersect(p2).isNotEmpty()
}

fun generarMenuSemanal(platos: List<String>, dias: List<String>): String {
    if (platos.size < dias.size * 2) return "⚠️ Se necesitan al menos ${dias.size * 2} platos para cubrir la semana sin repetir."
    val disponibles = platos.shuffled().toMutableList()
    val sb = StringBuilder()
    sb.appendLine("🗓️ MENÚ SEMANA")
    sb.appendLine()
    for (dia in dias) {
        val opcion1 = disponibles.removeAt(0)
        // busca el primer plato que no sea similar a opcion1
        val idx = disponibles.indexOfFirst { !sonSimilares(it, opcion1) }
        if (idx == -1) {
            // si no hay candidato distinto, toma el siguiente igual
            val opcion2 = disponibles.removeAt(0)
            sb.appendLine(dia)
            sb.appendLine("  1. $opcion1")
            sb.appendLine("  2. $opcion2")
        } else {
            val opcion2 = disponibles.removeAt(idx)
            sb.appendLine(dia)
            sb.appendLine("  1. $opcion1")
            sb.appendLine("  2. $opcion2")
        }
        sb.appendLine()
    }
    return sb.toString().trim()
}

// ═══════════════════════════════════════════════════════════════
// WORKER — PROCESA COLA CUANDO VUELVE LA RED
// ═══════════════════════════════════════════════════════════════

class ColaOfflineWorker(
    context: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dataStore = applicationContext.dataStore
        val prefs = dataStore.data.first()
        val colaStr = prefs[PrefKeys.COLA_OFFLINE] ?: "[]"
        if (JSONArray(colaStr).length() == 0) return Result.success()

        // Recuperar la IP guardada para construir el ApiClient
        val baseUrl = prefs[PrefKeys.RESTAURANT_NAME]?.let {
            "http://192.168.1.21:5000"
        } ?: "http://192.168.1.21:5000"

        val apiClient = ApiClient(baseUrl)
        val arr = JSONArray(colaStr)
        val procesadas = mutableListOf<String>()

        for (i in 0 until arr.length()) {
            val op = operacionPendienteFromJson(arr.getJSONObject(i))
            val p = JSONObject(op.payload)
            val resultado = when (op.tipo) {
                TipoOperacion.ACTUALIZAR_ESTADO ->
                    apiClient.updateOrderStatus(p.getInt("order_id"), p.getString("status"))
                TipoOperacion.ASIGNAR_MESA ->
                    apiClient.assignTableAndStart(p.getInt("order_id"), p.getInt("table_id"))
                TipoOperacion.AGREGAR_ITEM ->
                    apiClient.addItemToOrder(
                        p.getInt("order_id"), p.getInt("menu_item_id"),
                        p.getInt("quantity"), p.getString("notes"),
                        if (p.has("unit_price")) p.getDouble("unit_price") else null
                    )
                TipoOperacion.ELIMINAR_ITEM ->
                    apiClient.removeItemFromOrder(p.getInt("order_id"), p.getInt("item_id"))
                TipoOperacion.CREAR_COMANDA ->
                    kotlin.Result.success("omitida — reintento via withRetry")
            }
            resultado.onSuccess { procesadas.add(op.id) }
        }

        if (procesadas.isNotEmpty()) {
            val nuevaArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("id") !in procesadas) nuevaArr.put(obj)
            }
            dataStore.edit { it[PrefKeys.COLA_OFFLINE] = nuevaArr.toString() }
        }

        return Result.success()
    }
}

fun programarWorkerCola(context: android.content.Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = OneTimeWorkRequestBuilder<ColaOfflineWorker>()
        .setConstraints(constraints)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "cola_offline",
        ExistingWorkPolicy.REPLACE,
        request
    )
}

// ═══════════════════════════════════════════════════════════════
// ACTIVITY PRINCIPAL
// ═══════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si hay operaciones pendientes de sesiones anteriores, programar el worker
        programarWorkerCola(applicationContext)
        setContent {
            MaterialTheme {
                RestaurantApp()
            }
        }
    }
}

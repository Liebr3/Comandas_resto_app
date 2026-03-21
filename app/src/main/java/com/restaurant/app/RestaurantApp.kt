package com.restaurant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    val notes: String = ""
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

// ═══════════════════════════════════════════════════════════════
// CLIENTE API REST
// ═══════════════════════════════════════════════════════════════

class ApiClient(private val baseUrl: String) {

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
    }

    suspend fun addItemToOrder(orderId: Int, menuItemId: Int, quantity: Int, notes: String): Result<String> = runCatching {
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

    suspend fun removeItemFromOrder(orderId: Int, itemId: Int): Result<String> = runCatching {
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
    }
}

// ═══════════════════════════════════════════════════════════════
// VIEWMODEL
// ═══════════════════════════════════════════════════════════════

class RestaurantViewModel(private val apiClient: ApiClient) : ViewModel() {

    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    var menuItems by mutableStateOf(listOf<MenuItem>())
    var tables by mutableStateOf(listOf<Table>())
    var orders by mutableStateOf(listOf<Order>())

    var historial by mutableStateOf(listOf<HistorialEntry>())
    var claveSeguridad by mutableStateOf("1234")

    // Guarniciones configurables del día
    var guarnicionesDelDia by mutableStateOf(listOf<String>())

    var currentOrder by mutableStateOf(listOf<OrderItem>())
    var selectedTable by mutableStateOf<Table?>(null)

    init { loadInitialData() }

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
                .onSuccess { result -> withContext(Dispatchers.Main) { orders = result } }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al cargar comandas: ${error.message}" } }
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    fun addItemToCurrentOrder(menuItem: MenuItem, notes: String = "") {
        val existing = currentOrder.find { it.menuItem.id == menuItem.id && it.notes == notes }
        currentOrder = if (existing != null) {
            currentOrder.map {
                if (it.menuItem.id == menuItem.id && it.notes == notes) it.copy(quantity = it.quantity + 1) else it
            }
        } else {
            currentOrder + OrderItem(menuItem, 1, notes)
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
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true; errorMessage = null }
            apiClient.createOrder(table.id, currentOrder)
                .onSuccess {
                    withContext(Dispatchers.Main) { currentOrder = listOf(); selectedTable = null }
                    loadOrders()
                }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al crear comanda: ${error.message}" } }
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    fun updateOrderStatus(orderId: Int, status: String) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.updateOrderStatus(orderId, status)
                .onSuccess { loadOrders() }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al actualizar: ${error.message}" } }
        }
    }

    fun addItemToExistingOrder(orderId: Int, menuItem: MenuItem, notes: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.addItemToOrder(orderId, menuItem.id, 1, notes)
                .onSuccess { loadOrders() }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al agregar item: ${error.message}" } }
        }
    }

    fun removeItemFromExistingOrder(orderId: Int, itemId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            apiClient.removeItemFromOrder(orderId, itemId)
                .onSuccess { loadOrders() }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al eliminar item: ${error.message}" } }
        }
    }

    fun guardarEnHistorial(order: Order) {
        val propina = order.total * 0.10
        val totalConPropina = order.total + propina
        val ahora = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = HistorialEntry(order = order, propina = propina, totalConPropina = totalConPropina, fechaGuardado = ahora)
        historial = listOf(entry) + historial
        updateOrderStatus(order.orderId, "cancelled")
    }

    fun borrarDelHistorial(entry: HistorialEntry) {
        historial = historial.filter { it != entry }
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
    "Mojito frutal" to listOf("Frambuesa", "Frutilla", "Mango", "Piña")
)

// Productos que usan el diálogo de Menú completo (entrada + guarnición)
val ITEMS_CON_MENU = listOf("Menu normal", "Menu Extra")

// Entradas fijas siempre disponibles
val ENTRADAS_FIJAS = listOf("Crema de zapallo", "Consomé", "Ensalada", "Sin entrada")

// ═══════════════════════════════════════════════════════════════
// SPLASH SCREEN
// ═══════════════════════════════════════════════════════════════

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3500)
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
    val apiClient = remember { ApiClient(API_BASE_URL) }
    val viewModel = remember { RestaurantViewModel(apiClient) }
    var currentScreen by remember { mutableStateOf("menu") }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(icon = { Icon(Icons.Default.Restaurant, null) }, label = { Text("Menú") },
                    selected = currentScreen == "menu", onClick = { currentScreen = "menu" })
                NavigationBarItem(icon = { Icon(Icons.Default.ListAlt, null) }, label = { Text("Comandas") },
                    selected = currentScreen == "orders", onClick = { currentScreen = "orders" })
                NavigationBarItem(icon = { Icon(Icons.Default.History, null) }, label = { Text("Historial") },
                    selected = currentScreen == "historial", onClick = { currentScreen = "historial" })
                NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Config") },
                    selected = currentScreen == "config", onClick = { currentScreen = "config" })
            }
        }
    ) { paddingValues ->
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
                    confirmButton = { TextButton(onClick = { viewModel.errorMessage = null }) { Text("OK") } }
                )
            }
            if (viewModel.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
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
    guarniciones: List<String>,
    onConfirm: (notas: String) -> Unit,
    onDismiss: () -> Unit
) {
    val entradaSeleccionada = remember { mutableStateOf<String?>(null) }
    val guarnicionSeleccionada = remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
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
                Divider()
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
            }
        },
        confirmButton = {
            Button(onClick = {
                val partes = mutableListOf<String>()
                entradaSeleccionada.value?.let { partes.add("Entrada: $it") }
                guarnicionSeleccionada.value?.let { partes.add("Guarnición: $it") }
                onConfirm(partes.joinToString(" | "))
            }) { Text("Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ═══════════════════════════════════════════════════════════════
// PANTALLA MENÚ
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(viewModel: RestaurantViewModel) {
    var showTableSelector by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var itemWithOptions by remember { mutableStateOf<MenuItem?>(null) }
    var itemWithMenuCompleto by remember { mutableStateOf<MenuItem?>(null) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Menú del Restaurante") },
            actions = {
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
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Comanda actual:", fontWeight = FontWeight.Bold)
                    viewModel.currentOrder.forEach { orderItem ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row {
                                    Text("${orderItem.quantity}x ${orderItem.menuItem.name}")
                                    if (orderItem.notes.isNotEmpty()) {
                                        Text(" (${orderItem.notes})", fontSize = 12.sp,
                                            fontStyle = FontStyle.Italic,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.removeItemFromCurrentOrder(orderItem.menuItem, orderItem.notes) }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Quitar uno")
                                }
                                IconButton(onClick = { viewModel.addItemToCurrentOrder(orderItem.menuItem, orderItem.notes) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Agregar uno")
                                }
                                IconButton(onClick = {
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
                    val total = viewModel.currentOrder.sumOf { it.quantity * it.menuItem.price }
                    Text("Total: ${formatCLP(total)}", fontWeight = FontWeight.Bold)
                    Button(onClick = { showTableSelector = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Asignar a mesa")
                    }
                }
            }
        }

        val itemsByCategory = filteredItems.groupBy { it.category }

        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsByCategory.forEach { (category, _) ->
                item {
                    Text(text = category, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                items(filteredItems.filter { it.category == category }) { item ->
                    MenuItemCard(item) {
                        when {
                            ITEMS_CON_MENU.any { it.equals(item.name, ignoreCase = true) } -> {
                                itemWithMenuCompleto = item
                            }
                            ITEM_OPTIONS.containsKey(item.name) -> {
                                itemWithOptions = item
                            }
                            else -> viewModel.addItemToCurrentOrder(item)
                        }

                    }
                }
            }
        }
    }

    itemWithOptions?.let { item ->
        ItemOptionsDialog(
            item = item,
            options = ITEM_OPTIONS[item.name] ?: emptyList(),
            onConfirm = { selectedOptions ->
                viewModel.addItemToCurrentOrder(item, selectedOptions)
                itemWithOptions = null
            },
            onDismiss = { itemWithOptions = null }
        )
    }

    itemWithMenuCompleto?.let { item ->
        MenuCompletoDialog(
            item = item,
            guarniciones = viewModel.guarnicionesDelDia,
            onConfirm = { notas ->
                viewModel.addItemToCurrentOrder(item, notas)
                itemWithMenuCompleto = null
            },
            onDismiss = { itemWithMenuCompleto = null }
        )
    }

    if (showTableSelector) {
        AlertDialog(
            onDismissRequest = { showTableSelector = false },
            title = { Text("Seleccionar mesa") },
            text = {
                LazyColumn {
                    items(viewModel.tables) { table ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(4.dp).clickable {
                                viewModel.saveOrder(table)
                                showTableSelector = false
                            }
                        ) {
                            Text("Mesa ${table.name} (${table.capacity} personas)", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTableSelector = false }) { Text("Cancelar") } }
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


// Etiqueta visual para ítems prioritarios
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

    val activeOrders = viewModel.orders.filter { it.status in listOf("pending", "in_progress") }
    val terminatedOrders = viewModel.orders.filter { it.status == "completed" }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Comandas") },
            actions = {
                IconButton(onClick = { viewModel.loadOrders() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                }
            }
        )

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; selectedOrder = null },
                text = { Text("Activas (${activeOrders.size})") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; selectedOrder = null },
                text = { Text("Terminadas (${terminatedOrders.size})") })
        }

        val ordersToShow = if (selectedTab == 0) activeOrders else terminatedOrders

        if (selectedOrder == null) {
            if (ordersToShow.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (selectedTab == 0) "No hay comandas activas" else "No hay comandas terminadas hoy",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ordersToShow) { order ->
                        OrderCard(order, showPrice = selectedTab == 1) { selectedOrder = order }
                    }
                }
            }
        } else {
            OrderDetail(
                order = selectedOrder!!,
                viewModel = viewModel,
                isTerminada = selectedTab == 1,
                onBack = { selectedOrder = null }
            )
        }
    }
}

@Composable
fun OrderCard(order: Order, showPrice: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when (order.status) {
                "pending" -> MaterialTheme.colorScheme.errorContainer
                "in_progress" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (order.tableNumber == "Para llevar") "Para llevar" else "Mesa ${order.tableNumber}",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = order.items.joinToString(", ") { "${it.quantity}x ${it.name}" }, fontSize = 16.sp)
                Text(text = order.createdAt, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showPrice) {
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

    val currentOrder = viewModel.orders.find { it.orderId == order.orderId } ?: order
    val propina = currentOrder.total * 0.10
    val totalConPropina = currentOrder.total + propina

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
            }
            Text("Orden #${currentOrder.orderId}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            // Botón editar solo disponible en estado in_progress
            if (currentOrder.status == "in_progress" && !isTerminada) {
                IconButton(onClick = { modoEdicion = !modoEdicion }) {
                    Icon(
                        if (modoEdicion) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = if (modoEdicion) "Finalizar edición" else "Editar comanda",
                        tint = if (modoEdicion) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
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

            Card(modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Items actuales:", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    currentOrder.items.forEach { item ->
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

            LazyColumn(
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (currentOrder.tableNumber == "Para llevar") "Para llevar" else "Mesa ${currentOrder.tableNumber}",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )
                    Text("Estado: ${when(currentOrder.status) {
                        "pending" -> "Pendiente"
                        "in_progress" -> "En preparación"
                        "completed" -> "Terminada"
                        "cancelled" -> "Cancelada"
                        else -> currentOrder.status
                    }}")
                    Text("Hora: ${currentOrder.createdAt}", fontSize = 14.sp)

                    Divider(modifier = Modifier.padding(vertical = 16.dp))

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

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

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
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TOTAL:", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(formatCLP(totalConPropina), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (currentOrder.status == "pending") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                viewModel.updateOrderStatus(currentOrder.orderId, "in_progress")
                                viewModel.loadOrders()
                            }, modifier = Modifier.weight(1f)) { Text("Iniciar") }
                            OutlinedButton(onClick = { /* imprimir — pendiente */ }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Imprimir")
                            }
                        }
                    }

                    if (currentOrder.status == "in_progress") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                viewModel.updateOrderStatus(currentOrder.orderId, "completed")
                                viewModel.loadOrders()
                                onBack()
                            }, modifier = Modifier.weight(1f)) { Text("Terminar") }
                            OutlinedButton(onClick = { /* imprimir — pendiente */ }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Imprimir")
                            }
                        }
                    }

                    if (currentOrder.status == "completed") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { /* imprimir — pendiente */ }, modifier = Modifier.weight(1f)) {
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
                    }
                }
            }
        }
    }

    // Diálogos en modo edición
    itemWithOptions?.let { item ->
        ItemOptionsDialog(
            item = item,
            options = ITEM_OPTIONS[item.name] ?: emptyList(),
            onConfirm = { selectedOptions ->
                viewModel.addItemToExistingOrder(currentOrder.orderId, item, selectedOptions)
                itemWithOptions = null
            },
            onDismiss = { itemWithOptions = null }
        )
    }

    itemWithMenuCompleto?.let { item ->
        MenuCompletoDialog(
            item = item,
            guarniciones = viewModel.guarnicionesDelDia,
            onConfirm = { notas ->
                viewModel.addItemToExistingOrder(currentOrder.orderId, item, notas)
                itemWithMenuCompleto = null
            },
            onDismiss = { itemWithMenuCompleto = null }
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
}

// ═══════════════════════════════════════════════════════════════
// PANTALLA HISTORIAL
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorialScreen(viewModel: RestaurantViewModel) {
    var selectedEntry by remember { mutableStateOf<HistorialEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Historial del día") })

        if (selectedEntry == null) {
            if (viewModel.historial.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay comandas guardadas hoy", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(viewModel.historial.size) { index ->
                        val entry = viewModel.historial[index]
                        val colorFondo = verdesHistorial[index % verdesHistorial.size]
                        HistorialCard(entry = entry, colorFondo = colorFondo) { selectedEntry = entry }
                    }
                }
            }
        } else {
            HistorialDetail(entry = selectedEntry!!, viewModel = viewModel, onBack = { selectedEntry = null })
        }
    }
}

@Composable
fun HistorialCard(entry: HistorialEntry, colorFondo: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colorFondo)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = entry.fechaGuardado, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (entry.order.tableNumber == "Para llevar") "Para llevar" else "Mesa ${entry.order.tableNumber}",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                Text(text = entry.order.items.joinToString(", ") { "${it.quantity}x ${it.name}" }, fontSize = 14.sp)
            }
            Text(text = formatCLP(entry.totalConPropina), fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") }
            Text("Orden #${entry.order.orderId}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = entry.fechaGuardado, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (entry.order.tableNumber == "Para llevar") "Para llevar" else "Mesa ${entry.order.tableNumber}",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold
                )
                Text("Estado: Guardada", fontSize = 14.sp)

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                entry.order.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.quantity}x ${item.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(formatCLP(item.subtotal), fontWeight = FontWeight.Bold)
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

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
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TOTAL:", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(formatCLP(entry.totalConPropina), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { /* imprimir — pendiente */ }, modifier = Modifier.weight(1f)) {
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
                            viewModel.guarnicionesDelDia = guarnicionesInput
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
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
                        onClick = { viewModel.claveSeguridad = claveInput; claveSaved = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (claveSaved) "✓ Clave guardada" else "Guardar clave")
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
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// ACTIVITY PRINCIPAL
// ═══════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RestaurantApp()
            }
        }
    }
}

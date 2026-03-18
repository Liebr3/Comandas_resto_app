package com.restaurant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ═══════════════════════════════════════════════════════════════
// MODELOS DE DATOS (adaptados al esquema de tu base de datos)
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

// ═══════════════════════════════════════════════════════════════
// CLIENTE API REST
// ═══════════════════════════════════════════════════════════════

class ApiClient(private val baseUrl: String) {
    
    // GET /menu - Obtener todos los items del menú
    suspend fun getMenu(): Result<List<MenuItem>> = runCatching {

        android.util.Log.d("API_DEBUG", "Intentando conectar a: $baseUrl/menu")
        val url = URL("$baseUrl/menu")
        val connection = url.openConnection() as HttpURLConnection
        
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        android.util.Log.d("API_DEBUG", "Respuesta recibida: ${response.take(200)}")
        
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
        result.onFailure {
            android.util.Log.e("API_DEBUG", "FALLO en getMenu: ${it::class.simpleName}: ${it.message}")
        }
        result.onSuccess {
            android.util.Log.d("API_DEBUG", "EXITO: ${it.size} items cargados")
        }
    }
    
    // GET /tables - Obtener todas las mesas (necesitarás agregarlo al API)
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
    
    // POST /orders - Crear una nueva comanda
    suspend fun createOrder(tableId: Int, items: List<OrderItem>): Result<String> = runCatching {
        android.util.Log.d("API_DEBUG", "Creando comanda para mesa $tableId")
        val url = URL("$baseUrl/orders")
        val connection = url.openConnection() as HttpURLConnection

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        // Construir JSON body
        val itemsArray = JSONArray()
        items.forEach { orderItem ->
            val itemJson = JSONObject()
            itemJson.put("menu_item_id", orderItem.menuItem.id)
            itemJson.put("quantity", orderItem.quantity)
            itemJson.put("notes", orderItem.notes)
            itemsArray.put(itemJson)
        }

        val body = JSONObject()          // ← FALTABA ESTO
        body.put("table_id", tableId)    // ← FALTABA ESTO
        body.put("items", itemsArray)    // ← FALTABA ESTO

        android.util.Log.d("API_DEBUG", "JSON enviado: $body")

        // Escribir body al outputStream   ← NUEVO CON FLUSH
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        // Leer respuesta aunque sea error HTTP
        val responseCode = connection.responseCode
        android.util.Log.d("API_DEBUG", "HTTP response code: $responseCode")

        val response = if (responseCode >= 400) {
            connection.errorStream.bufferedReader().readText()
        } else {
            connection.inputStream.bufferedReader().readText()
        }

        android.util.Log.d("API_DEBUG", "Respuesta del servidor: $response")
        connection.disconnect()

        val json = JSONObject(response)
        json.getString("message")
    }.also { result ->
        result.onFailure {
            android.util.Log.e("API_DEBUG", "FALLO en createOrder: ${it::class.simpleName}: ${it.message}")
        }
    }
    
    // GET /orders - Obtener comandas activas
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
    
    // PATCH /orders/<id> - Actualizar estado de una comanda
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
        
        connection.outputStream.write(body.toString().toByteArray())
        
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        
        val json = JSONObject(response)
        json.getString("message")
    }
}

// ═══════════════════════════════════════════════════════════════
// VIEWMODEL (conectado al API)
// ═══════════════════════════════════════════════════════════════

class RestaurantViewModel(private val apiClient: ApiClient) : ViewModel() {
    
    // Estado de carga
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    
    // Datos del servidor
    var menuItems by mutableStateOf(listOf<MenuItem>())
    var tables by mutableStateOf(listOf<Table>())
    var orders by mutableStateOf(listOf<Order>())
    
    // Comanda temporal en construcción
    var currentOrder by mutableStateOf(listOf<OrderItem>())
    var selectedTable by mutableStateOf<Table?>(null)
    
    init {
        loadInitialData()
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
                .onSuccess { result -> withContext(Dispatchers.Main) { orders = result } }
                .onFailure { error -> withContext(Dispatchers.Main) { errorMessage = "Error al cargar comandas: ${error.message}" } }

            withContext(Dispatchers.Main) { isLoading = false }
        }
    }
    
    fun addItemToCurrentOrder(menuItem: MenuItem, notes: String = "") {
        val existing = currentOrder.find { it.menuItem.id == menuItem.id && it.notes == notes }
        currentOrder = if (existing != null) {
            currentOrder.map {
                if (it.menuItem.id == menuItem.id && it.notes == notes) {
                    it.copy(quantity = it.quantity + 1)
                } else it
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
                    if (it.menuItem.id == menuItem.id && it.notes == notes)
                        it.copy(quantity = it.quantity - 1)
                    else it
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
}

// ═══════════════════════════════════════════════════════════════
// COMPOSABLES - INTERFAZ DE USUARIO
// ═══════════════════════════════════════════════════════════════

@Composable
fun RestaurantApp() {
    // IMPORTANTE: Cambia esta IP por la IP de tu Raspberry Pi
    val API_BASE_URL = "http://192.168.1.21:5000"
    
    val apiClient = remember { ApiClient(API_BASE_URL) }
    val viewModel = remember { RestaurantViewModel(apiClient) }
    
    var currentScreen by remember { mutableStateOf("menu") }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Restaurant, contentDescription = null) },
                    label = { Text("Menú") },
                    selected = currentScreen == "menu",
                    onClick = { currentScreen = "menu" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.ListAlt, contentDescription = null) },
                    label = { Text("Comandas") },
                    selected = currentScreen == "orders",
                    onClick = { currentScreen = "orders" }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Config") },
                    selected = currentScreen == "config",
                    onClick = { currentScreen = "config" }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                "menu" -> MenuScreen(viewModel)
                "orders" -> OrdersScreen(viewModel)
                "config" -> ConfigScreen(viewModel)
            }
            
            // Mostrar errores
            viewModel.errorMessage?.let { error ->
                AlertDialog(
                    onDismissRequest = { viewModel.errorMessage = null },
                    title = { Text("Error") },
                    text = { Text(error) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.errorMessage = null }) {
                            Text("OK")
                        }
                    }
                )
            }
            
            // Indicador de carga
            if (viewModel.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(viewModel: RestaurantViewModel) {
    var showTableSelector by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Filtrar items según búsqueda
    val filteredItems = if (searchQuery.isBlank()) {
        viewModel.menuItems
    } else {
        viewModel.menuItems.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Menú del Restaurante") },
            actions = {
                IconButton(onClick = { viewModel.loadMenu() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                }
                IconButton(onClick = { }) {
                    Icon(Icons.Default.Print, contentDescription = "Imprimir")
                }
            }
        )

        // Campo de búsqueda
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
        // Items en la comanda actual
        if (viewModel.currentOrder.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Comanda actual:", fontWeight = FontWeight.Bold)
                    viewModel.currentOrder.forEach { orderItem ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${orderItem.quantity}x ${orderItem.menuItem.name}")
                                if (orderItem.notes.isNotEmpty()) {
                                    Text("  Nota: ${orderItem.notes}", fontSize = 12.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Botón quitar una unidad
                                IconButton(onClick = {
                                    viewModel.removeItemFromCurrentOrder(orderItem.menuItem, orderItem.notes)
                                }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Quitar uno")
                                }
                                // Botón agregar una unidad
                                IconButton(onClick = {
                                    viewModel.addItemToCurrentOrder(orderItem.menuItem, orderItem.notes)
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Agregar uno")
                                }
                                // Botón eliminar item completo
                                IconButton(onClick = {
                                    viewModel.currentOrder = viewModel.currentOrder.filter {
                                        !(it.menuItem.id == orderItem.menuItem.id && it.notes == orderItem.notes)
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Eliminar",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val total = viewModel.currentOrder.sumOf {
                        it.quantity * it.menuItem.price
                    }
                    Text("Total: $${total}", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showTableSelector = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Asignar a mesa")
                    }
                }
            }
        }
        
        // Agrupar items por categoría
        val itemsByCategory = filteredItems.groupBy { it.category }
        
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsByCategory.forEach { (category, items) ->
                item {
                    Text(
                        text = category,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(items) { item ->
                    MenuItemCard(item) {
                        viewModel.addItemToCurrentOrder(item)
                    }
                }
            }
        }
    }
    
    // Selector de mesa
    if (showTableSelector) {
        AlertDialog(
            onDismissRequest = { showTableSelector = false },
            title = { Text("Seleccionar mesa") },
            text = {
                LazyColumn {
                    items(viewModel.tables) { table ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp)
                                .clickable {
                                    viewModel.saveOrder(table)
                                    showTableSelector = false
                                }
                        ) {
                            Text(
                                "Mesa ${table.name} (${table.capacity} personas)",
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTableSelector = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun MenuItemCard(item: MenuItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isAvailable) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (item.description.isNotEmpty()) {
                    Text(
                        text = item.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "$$${item.price}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(viewModel: RestaurantViewModel) {
    var selectedOrder by remember { mutableStateOf<Order?>(null) }
    var selectedTab by remember { mutableStateOf(0) }

    val activeOrders = viewModel.orders.filter { it.status in listOf("pending", "in_progress") }
    val completedOrders = viewModel.orders.filter { it.status == "completed" }

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
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0; selectedOrder = null },
                text = { Text("Activas (${activeOrders.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1; selectedOrder = null },
                text = { Text("Completadas (${completedOrders.size})") }
            )
        }

        val ordersToShow = if (selectedTab == 0) activeOrders else completedOrders

        if (selectedOrder == null) {
            if (ordersToShow.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedTab == 0) "No hay comandas activas" else "No hay comandas completadas hoy",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ordersToShow) { order ->
                        OrderCard(order) { selectedOrder = order }
                    }
                }
            }
        } else {
            OrderDetail(selectedOrder!!, viewModel) {
                selectedOrder = null
            }
        }
    }
}

@Composable
fun OrderCard(order: Order, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when (order.status) {
                "pending" -> MaterialTheme.colorScheme.errorContainer
                "in_progress" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Mesa ${order.tableNumber}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${order.items.sumOf { it.quantity }} items - ${order.status}",
                    fontSize = 14.sp
                )
                Text(
                    text = order.createdAt,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$$${order.total}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun OrderDetail(order: Order, viewModel: RestaurantViewModel, onBack: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Observar la orden actualizada desde el ViewModel en tiempo real
    val currentOrder = viewModel.orders.find { it.orderId == order.orderId } ?: order

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
            }
            Text("Orden #${currentOrder.orderId}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Mesa ${currentOrder.tableNumber}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Estado: ${when(currentOrder.status) {
                    "pending" -> "Pendiente"
                    "in_progress" -> "En preparación"
                    "completed" -> "Completada"
                    "cancelled" -> "Cancelada"
                    else -> currentOrder.status
                }}")
                Text("Hora: ${currentOrder.createdAt}", fontSize = 14.sp)

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                currentOrder.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${item.quantity}x ${item.name}")
                            if (item.notes.isNotEmpty()) {
                                Text("  ${item.notes}", fontSize = 12.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                            }
                        }
                        Text("$${item.subtotal}", fontWeight = FontWeight.Bold)
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("TOTAL:", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("$${currentOrder.total}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Botones según estado
                if (currentOrder.status == "pending") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.updateOrderStatus(currentOrder.orderId, "in_progress")
                                viewModel.loadOrders()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Iniciar")
                        }
                        OutlinedButton(
                            onClick = { /* imprimir — pendiente */ },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Imprimir")
                        }
                    }
                }

                if (currentOrder.status == "in_progress") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.updateOrderStatus(currentOrder.orderId, "completed")
                                viewModel.loadOrders()
                                onBack()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Completar")
                        }
                        OutlinedButton(
                            onClick = { /* imprimir — pendiente */ },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Imprimir")
                        }
                    }
                }

                if (currentOrder.status == "completed") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { /* imprimir — pendiente */ },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Imprimir")
                        }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Eliminar")
                        }
                    }
                }
            }
        }
    }

    // Diálogo de confirmación de eliminación
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Eliminar comanda") },
            text = { Text("¿Confirmas que deseas eliminar la orden #${currentOrder.orderId} de la mesa ${currentOrder.tableNumber}?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateOrderStatus(currentOrder.orderId, "cancelled")
                        showDeleteConfirm = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: RestaurantViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Configuración") })
        
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Servidor API", fontWeight = FontWeight.Bold)
                Text("Configure la IP de su Raspberry Pi en el código")
                Text("Ubicación: RestaurantApp() -> API_BASE_URL", fontSize = 12.sp)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { viewModel.loadMenu() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Recargar Menú")
                }
                
                Button(
                    onClick = { viewModel.loadOrders() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Recargar Comandas")
                }
            }
        }
        
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Estado del sistema", fontWeight = FontWeight.Bold)
                Text("Items en menú: ${viewModel.menuItems.size}")
                Text("Mesas: ${viewModel.tables.size}")
                Text("Comandas activas: ${viewModel.orders.size}")
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

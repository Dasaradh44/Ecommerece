package com.Project;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class EcommereceOrderEngine {

    public static void main(String[] args) {
        EcommerceSystem system = new EcommerceSystem();
        system.start();
    }
}

/* ==============================
   MAIN SYSTEM / CLI
   ============================== */
class EcommerceSystem {
    private final Scanner sc = new Scanner(System.in);

    private final ProductService productService = new ProductService();
    private final CartService cartService = new CartService(productService);
    private final OrderService orderService = new OrderService(productService, cartService);
    private final PaymentService paymentService = new PaymentService();
    private final EventService eventService = new EventService();
    private final AuditService auditService = new AuditService();
    private final FailureInjectionService failureService = new FailureInjectionService();
    private final FraudDetectionService fraudService = new FraudDetectionService();
    private final IdempotencyService idempotencyService = new IdempotencyService();

    public void start() {
        seedData();

        while (true) {
            cartService.releaseExpiredReservations(auditService);

            System.out.println("\n========== E-COMMERCE ORDER ENGINE ==========");
            System.out.println("1. Add Product");
            System.out.println("2. View Products");
            System.out.println("3. Add to Cart");
            System.out.println("4. Remove from Cart");
            System.out.println("5. View Cart");
            System.out.println("6. Apply Coupon");
            System.out.println("7. Place Order");
            System.out.println("8. Cancel Order");
            System.out.println("9. View Orders");
            System.out.println("10. Low Stock Alert");
            System.out.println("11. Return Product");
            System.out.println("12. Simulate Concurrent Users");
            System.out.println("13. View Logs");
            System.out.println("14. Trigger Failure Mode");
            System.out.println("0. Exit");
            System.out.print("Enter choice: ");

            int choice = readInt();

            switch (choice) {
                case 1 -> addProduct();
                case 2 -> viewProducts();
                case 3 -> addToCart();
                case 4 -> removeFromCart();
                case 5 -> viewCart();
                case 6 -> applyCoupon();
                case 7 -> placeOrder();
                case 8 -> cancelOrder();
                case 9 -> viewOrders();
                case 10 -> lowStockAlert();
                case 11 -> returnProduct();
                case 12 -> simulateConcurrentUsers();
                case 13 -> auditService.viewLogs();
                case 14 -> triggerFailureMode();
                case 0 -> {
                    System.out.println("Exiting... Thank you!");
                    return;
                }
                default -> System.out.println("Invalid choice!");
            }
        }
    }

    private void seedData() {
        productService.addProduct("P101", "Laptop", 55000, 10);
        productService.addProduct("P102", "Phone", 25000, 15);
        productService.addProduct("P103", "Headphones", 2000, 20);
        productService.addProduct("P104", "Mouse", 800, 8);
        productService.addProduct("P105", "Keyboard", 1500, 5);
    }

    private void addProduct() {
        System.out.print("Enter Product ID: ");
        String id = sc.nextLine().trim();
        System.out.print("Enter Product Name: ");
        String name = sc.nextLine().trim();
        System.out.print("Enter Price: ");
        double price = readDouble();
        System.out.print("Enter Stock: ");
        int stock = readInt();

        boolean added = productService.addProduct(id, name, price, stock);
        if (added) {
            auditService.log("ADMIN added product " + id);
            System.out.println("Product added successfully.");
        } else {
            System.out.println("Duplicate Product ID / Invalid stock.");
        }
    }

    private void viewProducts() {
        productService.viewProducts();
    }

    private void addToCart() {
        System.out.print("Enter User ID: ");
        String userId = sc.nextLine().trim();
        System.out.print("Enter Product ID: ");
        String productId = sc.nextLine().trim();
        System.out.print("Enter Quantity: ");
        int qty = readInt();

        boolean success = cartService.addToCart(userId, productId, qty, auditService);
        System.out.println(success ? "Added to cart." : "Failed to add to cart.");
    }

    private void removeFromCart() {
        System.out.print("Enter User ID: ");
        String userId = sc.nextLine().trim();
        System.out.print("Enter Product ID: ");
        String productId = sc.nextLine().trim();

        boolean success = cartService.removeFromCart(userId, productId, auditService);
        System.out.println(success ? "Removed from cart." : "Failed to remove.");
    }

    private void viewCart() {
        System.out.print("Enter User ID: ");
        String userId = sc.nextLine().trim();
        cartService.viewCart(userId);
    }

    private void applyCoupon() {
        System.out.print("Enter User ID: ");
        String userId = sc.nextLine().trim();
        System.out.print("Enter Coupon Code (SAVE10 / FLAT200): ");
        String coupon = sc.nextLine().trim().toUpperCase();

        boolean applied = cartService.applyCoupon(userId, coupon);
        System.out.println(applied ? "Coupon applied successfully." : "Invalid coupon / already applied.");
    }

    private void placeOrder() {
        System.out.print("Enter User ID: ");
        String userId = sc.nextLine().trim();

        System.out.print("Enter Idempotency Key (example: ORDER-001): ");
        String key = sc.nextLine().trim();

        if (idempotencyService.isDuplicate(key)) {
            System.out.println("Duplicate order request blocked (Idempotency).");
            return;
        }

        try {
            if (failureService.shouldFail("ORDER_CREATION")) {
                throw new RuntimeException("Injected failure during order creation.");
            }

            Order order = orderService.createOrder(userId, auditService);
            if (order == null) {
                System.out.println("Order creation failed.");
                return;
            }

            eventService.addEvent("ORDER_CREATED -> " + order.getOrderId());

            order.setState(OrderState.PENDING_PAYMENT);
            auditService.log("ORDER " + order.getOrderId() + " pending payment");

            if (fraudService.isFraudulent(userId, order)) {
                System.out.println("⚠ Fraud Alert: Suspicious order detected.");
                auditService.log("Fraud alert for user " + userId + ", order " + order.getOrderId());
            }

            boolean paymentSuccess;
            if (failureService.shouldFail("PAYMENT")) {
                paymentSuccess = false;
            } else {
                paymentSuccess = paymentService.processPayment(order.getTotalAmount());
            }

            if (paymentSuccess) {
                order.setState(OrderState.PAID);
                eventService.addEvent("PAYMENT_SUCCESS -> " + order.getOrderId());
                auditService.log("Payment success for ORDER " + order.getOrderId());
                System.out.println("Order placed successfully! Order ID: " + order.getOrderId());
            } else {
                // Rollback
                System.out.println("Payment failed. Rolling back...");
                orderService.rollbackOrder(order, auditService);
                eventService.addEvent("PAYMENT_FAILED -> " + order.getOrderId());
                auditService.log("Payment failed. ORDER " + order.getOrderId() + " rolled back");
                System.out.println("Order failed and stock restored.");
            }

            idempotencyService.markProcessed(key);

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            auditService.log("System exception during order placement: " + e.getMessage());
        }
    }

    private void cancelOrder() {
        System.out.print("Enter Order ID: ");
        String orderId = sc.nextLine().trim();

        boolean success = orderService.cancelOrder(orderId, auditService);
        System.out.println(success ? "Order cancelled successfully." : "Cannot cancel order.");
    }

    private void viewOrders() {
        System.out.println("1. View All");
        System.out.println("2. Search by Order ID");
        System.out.println("3. Filter by Status");
        System.out.print("Enter choice: ");
        int ch = readInt();

        switch (ch) {
            case 1 -> orderService.viewAllOrders();
            case 2 -> {
                System.out.print("Enter Order ID: ");
                String orderId = sc.nextLine().trim();
                orderService.searchOrder(orderId);
            }
            case 3 -> {
                System.out.print("Enter Status (PAID/CANCELLED/FAILED): ");
                String status = sc.nextLine().trim().toUpperCase();
                orderService.filterOrders(status);
            }
            default -> System.out.println("Invalid option.");
        }
    }

    private void lowStockAlert() {
        productService.lowStockAlert();
    }

    private void returnProduct() {
        System.out.print("Enter Order ID: ");
        String orderId = sc.nextLine().trim();
        System.out.print("Enter Product ID: ");
        String productId = sc.nextLine().trim();
        System.out.print("Enter Return Quantity: ");
        int qty = readInt();

        boolean success = orderService.returnProduct(orderId, productId, qty, auditService);
        System.out.println(success ? "Return processed successfully." : "Return failed.");
    }

    private void simulateConcurrentUsers() {
        System.out.println("\n=== Concurrency Simulation ===");
        System.out.print("Enter Product ID: ");
        String productId = sc.nextLine().trim();

        Product product = productService.getProduct(productId);
        if (product == null) {
            System.out.println("Invalid product.");
            return;
        }

        System.out.print("Enter User A quantity: ");
        int qtyA = readInt();
        System.out.print("Enter User B quantity: ");
        int qtyB = readInt();

        Thread userA = new Thread(() -> {
            boolean result = cartService.addToCart("USER_A", productId, qtyA, auditService);
            System.out.println("USER_A add result: " + result);
        });

        Thread userB = new Thread(() -> {
            boolean result = cartService.addToCart("USER_B", productId, qtyB, auditService);
            System.out.println("USER_B add result: " + result);
        });

        userA.start();
        userB.start();

        try {
            userA.join();
            userB.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("Final stock state:");
        productService.viewProducts();
    }

    private void triggerFailureMode() {
        System.out.println("1. Fail Payment");
        System.out.println("2. Fail Order Creation");
        System.out.println("3. Fail Inventory Update");
        System.out.print("Choose failure mode: ");
        int ch = readInt();

        switch (ch) {
            case 1 -> failureService.setFailureMode("PAYMENT", true);
            case 2 -> failureService.setFailureMode("ORDER_CREATION", true);
            case 3 -> failureService.setFailureMode("INVENTORY_UPDATE", true);
            default -> System.out.println("Invalid choice.");
        }
        System.out.println("Failure mode activated.");
    }

    private int readInt() {
        while (true) {
            try {
                int value = Integer.parseInt(sc.nextLine().trim());
                return value;
            } catch (Exception e) {
                System.out.print("Enter valid integer: ");
            }
        }
    }

    private double readDouble() {
        while (true) {
            try {
                double value = Double.parseDouble(sc.nextLine().trim());
                return value;
            } catch (Exception e) {
                System.out.print("Enter valid number: ");
            }
        }
    }
}

/* ==============================
   PRODUCT
   ============================== */
class Product {
    private final String productId;
    private final String name;
    private final double price;
    private int availableStock;
    private int reservedStock;
    private final ReentrantLock lock = new ReentrantLock();

    public Product(String productId, String name, double price, int stock) {
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.availableStock = stock;
        this.reservedStock = 0;
    }

    public String getProductId() { return productId; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public int getAvailableStock() { return availableStock; }
    public int getReservedStock() { return reservedStock; }
    public ReentrantLock getLock() { return lock; }

    public boolean reserveStock(int qty) {
        lock.lock();
        try {
            if (qty <= 0 || availableStock < qty) return false;
            availableStock -= qty;
            reservedStock += qty;
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void releaseReservedStock(int qty) {
        lock.lock();
        try {
            reservedStock -= qty;
            availableStock += qty;
        } finally {
            lock.unlock();
        }
    }

    public void confirmReservedStock(int qty) {
        lock.lock();
        try {
            reservedStock -= qty;
        } finally {
            lock.unlock();
        }
    }

    public void restoreStock(int qty) {
        lock.lock();
        try {
            availableStock += qty;
        } finally {
            lock.unlock();
        }
    }

    public boolean updateStock(int stock) {
        if (stock < 0) return false;
        this.availableStock = stock;
        return true;
    }

    @Override
    public String toString() {
        return String.format("ID=%s | Name=%s | Price=₹%.2f | Available=%d | Reserved=%d",
                productId, name, price, availableStock, reservedStock);
    }
}

class ProductService {
    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public boolean addProduct(String id, String name, double price, int stock) {
        if (stock < 0 || products.containsKey(id)) return false;
        products.put(id, new Product(id, name, price, stock));
        return true;
    }

    public Product getProduct(String id) {
        return products.get(id);
    }

    public void viewProducts() {
        if (products.isEmpty()) {
            System.out.println("No products available.");
            return;
        }
        System.out.println("\n===== PRODUCTS =====");
        for (Product p : products.values()) {
            System.out.println(p);
        }
    }

    public void lowStockAlert() {
        System.out.println("\n===== LOW STOCK ALERT =====");
        boolean found = false;
        for (Product p : products.values()) {
            if (p.getAvailableStock() <= 5) {
                System.out.println(p);
                found = true;
            }
        }
        if (!found) System.out.println("No low stock products.");
    }

    public Collection<Product> getAllProducts() {
        return products.values();
    }
}

/* ==============================
   CART
   ============================== */
class CartItem {
    private final Product product;
    private int quantity;
    private LocalDateTime reservedAt;

    public CartItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
        this.reservedAt = LocalDateTime.now();
    }

    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public LocalDateTime getReservedAt() { return reservedAt; }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
        this.reservedAt = LocalDateTime.now();
    }

    public double getTotal() {
        return product.getPrice() * quantity;
    }
}

class Cart {
    private final String userId;
    private final Map<String, CartItem> items = new HashMap<>();
    private String couponCode;

    public Cart(String userId) {
        this.userId = userId;
    }

    public String getUserId() { return userId; }
    public Map<String, CartItem> getItems() { return items; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public void clear() {
        items.clear();
        couponCode = null;
    }
}

class CartService {
    private final Map<String, Cart> carts = new ConcurrentHashMap<>();
    private final ProductService productService;
    private static final long RESERVATION_EXPIRY_SECONDS = 120; // 2 minutes

    public CartService(ProductService productService) {
        this.productService = productService;
    }

    public Cart getOrCreateCart(String userId) {
        return carts.computeIfAbsent(userId, Cart::new);
    }

    public boolean addToCart(String userId, String productId, int qty, AuditService auditService) {
        if (qty <= 0) return false;

        Product product = productService.getProduct(productId);
        if (product == null) return false;

        Cart cart = getOrCreateCart(userId);
        CartItem existing = cart.getItems().get(productId);

        int additionalQty = qty;
        if (existing != null) {
            additionalQty = qty;
        }

        boolean reserved = product.reserveStock(additionalQty);
        if (!reserved) return false;

        if (existing == null) {
            cart.getItems().put(productId, new CartItem(product, qty));
        } else {
            existing.setQuantity(existing.getQuantity() + qty);
        }

        auditService.log(userId + " added " + productId + " qty=" + qty + " to cart");
        return true;
    }

    public boolean removeFromCart(String userId, String productId, AuditService auditService) {
        Cart cart = carts.get(userId);
        if (cart == null || !cart.getItems().containsKey(productId)) return false;

        CartItem item = cart.getItems().remove(productId);
        item.getProduct().releaseReservedStock(item.getQuantity());

        auditService.log(userId + " removed " + productId + " from cart");
        return true;
    }

    public void viewCart(String userId) {
        Cart cart = carts.get(userId);
        if (cart == null || cart.isEmpty()) {
            System.out.println("Cart is empty.");
            return;
        }

        System.out.println("\n===== CART : " + userId + " =====");
        double subtotal = 0;
        for (CartItem item : cart.getItems().values()) {
            System.out.printf("Product=%s | Qty=%d | Total=₹%.2f%n",
                    item.getProduct().getName(), item.getQuantity(), item.getTotal());
            subtotal += item.getTotal();
        }

        double discount = DiscountService.calculateDiscount(cart);
        double finalAmount = subtotal - discount;

        System.out.println("Subtotal: ₹" + subtotal);
        System.out.println("Discount: ₹" + discount);
        System.out.println("Final Amount: ₹" + finalAmount);
        System.out.println("Coupon: " + (cart.getCouponCode() == null ? "None" : cart.getCouponCode()));
    }

    public boolean applyCoupon(String userId, String couponCode) {
        Cart cart = carts.get(userId);
        if (cart == null || cart.isEmpty()) return false;

        if (!couponCode.equals("SAVE10") && !couponCode.equals("FLAT200")) {
            return false;
        }

        if (cart.getCouponCode() != null) return false; // avoid invalid combinations
        cart.setCouponCode(couponCode);
        return true;
    }

    public Cart getCart(String userId) {
        return carts.get(userId);
    }

    public void clearCart(String userId) {
        Cart cart = carts.get(userId);
        if (cart != null) cart.clear();
    }

    public void releaseExpiredReservations(AuditService auditService) {
        for (Cart cart : carts.values()) {
            Iterator<Map.Entry<String, CartItem>> iterator = cart.getItems().entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, CartItem> entry = iterator.next();
                CartItem item = entry.getValue();

                long seconds = Duration.between(item.getReservedAt(), LocalDateTime.now()).getSeconds();
                if (seconds >= RESERVATION_EXPIRY_SECONDS) {
                    item.getProduct().releaseReservedStock(item.getQuantity());
                    auditService.log("Reservation expired for " + cart.getUserId() + " product=" + item.getProduct().getProductId());
                    iterator.remove();
                }
            }
        }
    }
}

/* ==============================
   ORDER
   ============================== */
enum OrderState {
    CREATED,
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    FAILED,
    CANCELLED
}

class Order {
    private final String orderId;
    private final String userId;
    private final Map<String, Integer> items;
    private double totalAmount;
    private OrderState state;
    private final LocalDateTime createdAt;

    public Order(String orderId, String userId, Map<String, Integer> items, double totalAmount) {
        this.orderId = orderId;
        this.userId = userId;
        this.items = new HashMap<>(items);
        this.totalAmount = totalAmount;
        this.state = OrderState.CREATED;
        this.createdAt = LocalDateTime.now();
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public Map<String, Integer> getItems() { return items; }
    public double getTotalAmount() { return totalAmount; }
    public OrderState getState() { return state; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setTotalAmount(double totalAmount) {
        this.totalAmount = totalAmount;
    }

    public boolean setState(OrderState newState) {
        if (isValidTransition(this.state, newState)) {
            this.state = newState;
            return true;
        }
        return false;
    }

    private boolean isValidTransition(OrderState current, OrderState next) {
        return switch (current) {
            case CREATED -> next == OrderState.PENDING_PAYMENT || next == OrderState.FAILED;
            case PENDING_PAYMENT -> next == OrderState.PAID || next == OrderState.FAILED || next == OrderState.CANCELLED;
            case PAID -> next == OrderState.SHIPPED || next == OrderState.CANCELLED;
            case SHIPPED -> next == OrderState.DELIVERED;
            case DELIVERED, FAILED, CANCELLED -> false;
        };
    }

    @Override
    public String toString() {
        return String.format("OrderID=%s | User=%s | Total=₹%.2f | Status=%s | Time=%s",
                orderId, userId, totalAmount, state, createdAt);
    }
}

class OrderService {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final ProductService productService;
    private final CartService cartService;
    private int orderCounter = 1001;

    public OrderService(ProductService productService, CartService cartService) {
        this.productService = productService;
        this.cartService = cartService;
    }

    public Order createOrder(String userId, AuditService auditService) {
        Cart cart = cartService.getCart(userId);
        if (cart == null || cart.isEmpty()) {
            System.out.println("Cart is empty.");
            return null;
        }

        // Step 1: Validate cart
        Map<String, Integer> orderItems = new HashMap<>();
        double subtotal = 0;

        for (CartItem item : cart.getItems().values()) {
            orderItems.put(item.getProduct().getProductId(), item.getQuantity());
            subtotal += item.getTotal();
        }

        // Step 2: Calculate total
        double discount = DiscountService.calculateDiscount(cart);
        double finalAmount = subtotal - discount;

        // Step 3: Confirm reserved stock
        for (CartItem item : cart.getItems().values()) {
            item.getProduct().confirmReservedStock(item.getQuantity());
        }

        // Step 4: Create order
        String orderId = "ORD" + orderCounter++;
        Order order = new Order(orderId, userId, orderItems, finalAmount);
        orders.put(orderId, order);

        // Step 5: Clear cart
        cartService.clearCart(userId);

        auditService.log("ORDER " + orderId + " created for user " + userId);
        return order;
    }

    public void rollbackOrder(Order order, AuditService auditService) {
        if (order == null) return;

        for (Map.Entry<String, Integer> entry : order.getItems().entrySet()) {
            Product p = productService.getProduct(entry.getKey());
            if (p != null) {
                p.restoreStock(entry.getValue());
            }
        }

        order.setState(OrderState.FAILED);
        auditService.log("Rollback completed for ORDER " + order.getOrderId());
    }

    public boolean cancelOrder(String orderId, AuditService auditService) {
        Order order = orders.get(orderId);
        if (order == null) return false;

        if (order.getState() == OrderState.CANCELLED || order.getState() == OrderState.FAILED) {
            return false;
        }

        if (order.getState() == OrderState.SHIPPED || order.getState() == OrderState.DELIVERED) {
            return false;
        }

        for (Map.Entry<String, Integer> entry : order.getItems().entrySet()) {
            Product p = productService.getProduct(entry.getKey());
            if (p != null) {
                p.restoreStock(entry.getValue());
            }
        }

        order.setState(OrderState.CANCELLED);
        auditService.log("ORDER " + orderId + " cancelled");
        return true;
    }

    public boolean returnProduct(String orderId, String productId, int qty, AuditService auditService) {
        Order order = orders.get(orderId);
        if (order == null || qty <= 0) return false;
        if (!order.getItems().containsKey(productId)) return false;

        int purchasedQty = order.getItems().get(productId);
        if (qty > purchasedQty) return false;

        Product p = productService.getProduct(productId);
        if (p == null) return false;

        p.restoreStock(qty);
        order.getItems().put(productId, purchasedQty - qty);

        double refund = p.getPrice() * qty;
        order.setTotalAmount(order.getTotalAmount() - refund);

        auditService.log("Partial return processed for ORDER " + orderId + ", product=" + productId + ", qty=" + qty);
        return true;
    }

    public void viewAllOrders() {
        if (orders.isEmpty()) {
            System.out.println("No orders found.");
            return;
        }
        System.out.println("\n===== ALL ORDERS =====");
        for (Order o : orders.values()) {
            System.out.println(o);
        }
    }

    public void searchOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            System.out.println("Order not found.");
        } else {
            System.out.println(order);
        }
    }

    public void filterOrders(String status) {
        System.out.println("\n===== FILTERED ORDERS =====");
        boolean found = false;
        for (Order o : orders.values()) {
            if (o.getState().name().equalsIgnoreCase(status)) {
                System.out.println(o);
                found = true;
            }
        }
        if (!found) System.out.println("No matching orders.");
    }
}

/* ==============================
   PAYMENT
   ============================== */
class PaymentService {
    private final Random random = new Random();

    public boolean processPayment(double amount) {
        System.out.println("Processing payment of ₹" + amount + "...");
        return random.nextInt(100) < 75; // 75% success
    }
}

/* ==============================
   DISCOUNT
   ============================== */
class DiscountService {
    public static double calculateDiscount(Cart cart) {
        double subtotal = 0;
        double discount = 0;

        for (CartItem item : cart.getItems().values()) {
            subtotal += item.getTotal();

            if (item.getQuantity() > 3) {
                discount += item.getTotal() * 0.05; // extra 5%
            }
        }

        if (subtotal > 1000) {
            discount += subtotal * 0.10; // 10%
        }

        if (cart.getCouponCode() != null) {
            switch (cart.getCouponCode()) {
                case "SAVE10" -> discount += subtotal * 0.10;
                case "FLAT200" -> discount += 200;
            }
        }

        if (discount > subtotal) discount = subtotal;
        return discount;
    }
}

/* ==============================
   EVENTS
   ============================== */
class EventService {
    private final Queue<String> eventQueue = new LinkedList<>();

    public void addEvent(String event) {
        eventQueue.offer(event);
        processEvents();
    }

    private void processEvents() {
        while (!eventQueue.isEmpty()) {
            String event = eventQueue.poll();
            System.out.println("EVENT: " + event);
        }
    }
}

/* ==============================
   AUDIT LOG
   ============================== */
class AuditService {
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    public void log(String message) {
        logs.add("[" + LocalDateTime.now() + "] " + message);
    }

    public void viewLogs() {
        System.out.println("\n===== AUDIT LOGS =====");
        if (logs.isEmpty()) {
            System.out.println("No logs found.");
            return;
        }
        for (String log : logs) {
            System.out.println(log);
        }
    }
}

/* ==============================
   FRAUD DETECTION
   ============================== */
class FraudDetectionService {
    private final Map<String, List<LocalDateTime>> userOrders = new HashMap<>();

    public boolean isFraudulent(String userId, Order order) {
        userOrders.putIfAbsent(userId, new ArrayList<>());
        List<LocalDateTime> orderTimes = userOrders.get(userId);

        LocalDateTime now = LocalDateTime.now();
        orderTimes.add(now);

        long recentOrders = orderTimes.stream()
                .filter(time -> Duration.between(time, now).toMinutes() < 1)
                .count();

        return recentOrders >= 3 || order.getTotalAmount() > 100000;
    }
}

/* ==============================
   FAILURE INJECTION
   ============================== */
class FailureInjectionService {
    private final Map<String, Boolean> failureModes = new HashMap<>();

    public void setFailureMode(String type, boolean value) {
        failureModes.put(type, value);
    }

    public boolean shouldFail(String type) {
        boolean fail = failureModes.getOrDefault(type, false);
        if (fail) {
            failureModes.put(type, false); // fail only once
        }
        return fail;
    }
}

/* ==============================
   IDEMPOTENCY
   ============================== */
class IdempotencyService {
    private final Set<String> processedKeys = new HashSet<>();

    public boolean isDuplicate(String key) {
        return processedKeys.contains(key);
    }

    public void markProcessed(String key) {
        processedKeys.add(key);
    }
}
📌 Overview

The E-Commerce Order Engine is a console-based backend simulation of a real-world e-commerce system. It handles the complete lifecycle of orders, including product management, cart operations, payment processing, inventory handling, and concurrency control.

This project demonstrates system design concepts, Java concurrency, and fault-tolerant architecture, making it ideal for learning and hackathons.

🚀 Features
🛍️ Product Management
Add new products
View available products
Low stock alerts
🧺 Cart System
Add/remove items from cart
Stock reservation mechanism
Auto-expiry of reserved items (2 minutes)
📦 Order Management

Place orders with lifecycle tracking:

CREATED → PENDING_PAYMENT → PAID → SHIPPED → DELIVERED
Cancel orders
Return products (partial return supported)
Search and filter orders
💳 Payment Processing
Simulated payment gateway
75% success rate
Automatic rollback on failure
🎯 Discounts & Coupons
Auto discount (10% for orders > ₹1000)
Extra discount for bulk items
Coupons:
SAVE10 → 10% discount
FLAT200 → ₹200 off
⚡ Concurrency Handling
Multi-user simulation using threads
Safe stock updates using ReentrantLock
🔁 Idempotency Support
Prevents duplicate order processing
⚠️ Failure Injection
Simulate failures:
Payment failure
Order creation failure
Inventory update failure
🔍 Fraud Detection
Detects:
Multiple orders in short time
High-value suspicious orders
📜 Audit Logging
Tracks all system activities
View logs anytime
📡 Event Processing
Event-driven logs (ORDER_CREATED, PAYMENT_SUCCESS, etc.)
🏗️ Tech Stack
Java (JDK 21)
Collections Framework
Concurrency (Threads, Locks)
📂 Project Structure
com.Project
 ├── EcommereceOrderEngine (Main Class)
 ├── EcommerceSystem
 ├── Product / ProductService
 ├── Cart / CartService
 ├── Order / OrderService
 ├── PaymentService
 ├── DiscountService
 ├── EventService
 ├── AuditService
 ├── FraudDetectionService
 ├── FailureInjectionService
 └── IdempotencyService
 🧪 Sample Flow
Add products
Add items to cart
Apply coupon
Place order
Payment success/failure
View orders
⚠️ Important Concepts Implemented
✔ Thread Safety using ReentrantLock
✔ Inventory Reservation System
✔ Order State Machine
✔ Idempotent API Design
✔ Failure Recovery (Rollback)
✔ Event-driven architecture (basic)
🎯 Use Cases
Backend system design practice
Java concurrency learning
Hackathon project
Interview demonstration
📸 Sample Menu
========== E-COMMERCE ORDER ENGINE ==========
1. Add Product
2. View Products
3. Add to Cart
4. Remove from Cart
5. View Cart
6. Apply Coupon
7. Place Order
8. Cancel Order
9. View Orders
10. Low Stock Alert
11. Return Product
12. Simulate Concurrent Users
13. View Logs
14. Trigger Failure Mode
0. Exit

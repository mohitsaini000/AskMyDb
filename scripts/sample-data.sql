-- =========================================================
-- AskMyDb sample schema: a small e-commerce dataset
-- Run this once against the askmydb database (via IntelliJ's
-- DB console, connected to localhost:5434 / askmydb) so we
-- have real data to test natural-language questions against.
-- =========================================================

DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS customers;

CREATE TABLE customers (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    email       VARCHAR(150) NOT NULL,
    city        VARCHAR(100),
    signup_date DATE NOT NULL
);

CREATE TABLE products (
    id       SERIAL PRIMARY KEY,
    name     VARCHAR(150) NOT NULL,
    category VARCHAR(80),
    price    NUMERIC(10,2) NOT NULL
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    order_date  DATE NOT NULL,
    status      VARCHAR(30) NOT NULL  -- PLACED, SHIPPED, DELIVERED, CANCELLED
);

CREATE TABLE order_items (
    id         SERIAL PRIMARY KEY,
    order_id   INTEGER NOT NULL REFERENCES orders(id),
    product_id INTEGER NOT NULL REFERENCES products(id),
    quantity   INTEGER NOT NULL,
    unit_price NUMERIC(10,2) NOT NULL
);

-- ---------- sample data ----------

INSERT INTO customers (name, email, city, signup_date) VALUES
('Rohit Sharma', 'rohit@example.com', 'Mumbai', '2025-01-15'),
('Priya Verma', 'priya@example.com', 'Delhi', '2025-02-10'),
('Aman Gupta', 'aman@example.com', 'Bengaluru', '2025-03-05'),
('Sneha Iyer', 'sneha@example.com', 'Chennai', '2025-04-20'),
('Karan Mehta', 'karan@example.com', 'Mumbai', '2025-05-12'),
('Divya Nair', 'divya@example.com', 'Bengaluru', '2025-06-01');

INSERT INTO products (name, category, price) VALUES
('Wireless Mouse', 'Electronics', 599.00),
('Mechanical Keyboard', 'Electronics', 2999.00),
('Office Chair', 'Furniture', 6499.00),
('Notebook Set', 'Stationery', 249.00),
('Water Bottle', 'Lifestyle', 399.00),
('Desk Lamp', 'Furniture', 1199.00);

INSERT INTO orders (customer_id, order_date, status) VALUES
(1, '2026-07-02', 'DELIVERED'),
(1, '2026-08-10', 'SHIPPED'),
(2, '2026-07-15', 'DELIVERED'),
(3, '2026-08-01', 'PLACED'),
(4, '2026-06-25', 'DELIVERED'),
(5, '2026-08-05', 'CANCELLED'),
(6, '2026-07-28', 'DELIVERED'),
(2, '2026-08-12', 'SHIPPED');

INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
(1, 1, 2, 599.00),
(1, 4, 3, 249.00),
(2, 2, 1, 2999.00),
(3, 3, 1, 6499.00),
(4, 5, 4, 399.00),
(5, 2, 1, 2999.00),
(6, 6, 2, 1199.00),
(7, 1, 1, 599.00),
(7, 3, 1, 6499.00),
(8, 4, 5, 249.00);

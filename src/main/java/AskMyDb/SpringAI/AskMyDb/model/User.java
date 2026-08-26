package AskMyDb.SpringAI.AskMyDb.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A real JPA entity - deliberately different from the rest of this codebase
// (which reads business tables dynamically via raw JDBC, since we don't
// control their shape). This table IS ours: we own its structure, we know
// it ahead of time, and it will never change out from under us the way an
// arbitrary connected database's schema might. That's exactly the situation
// JPA/Hibernate is designed for, so we use it here instead of hand-rolling
// SQL for something this well-defined.
//
// Named "app_users" (not "users") so it reads unambiguously as AskMyDb's
// own bookkeeping table - the same reasoning as schema_embeddings and
// schema_index_state. It's also added to SchemaService.INTERNAL_TABLES so
// the NL-to-SQL/RAG layer never treats it as a queryable business table or
// embeds it - nobody should be able to ask AskMyDb "list all users and
// their passwords" and have it comply, even though the password column
// only ever holds a BCrypt hash, never plain text.
@Entity
@Table(name = "app_users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    // Always a BCrypt hash (see AuthService) - the raw password is never
    // stored, logged, or held in memory longer than it takes to hash it.
    @Column(nullable = false)
    private String password;

    // JPA requires a no-arg constructor to construct entities via
    // reflection when loading rows back out of the database.
    protected User() {
    }

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}

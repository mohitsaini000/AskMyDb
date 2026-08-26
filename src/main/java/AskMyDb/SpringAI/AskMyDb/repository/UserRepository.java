package AskMyDb.SpringAI.AskMyDb.repository;

import AskMyDb.SpringAI.AskMyDb.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// Spring Data JPA generates the implementation of this interface at
// runtime - we only declare the method signatures we need, and Spring
// derives the SQL from the method name itself (e.g. findByUsername ->
// "SELECT * FROM app_users WHERE username = ?"). No SQL written by hand,
// unlike the rest of this codebase - this is the trade-off JPA makes for
// a table whose shape we fully control.
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}

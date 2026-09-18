package tacos.security;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import org.springframework.security.crypto.password.PasswordEncoder;

import lombok.Data;
import tacos.User;

@Data
public class RegistrationForm {

  @NotBlank(message = "Username is required")
  @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
  private String username;

  @NotBlank(message = "Password is required")
  @Size(min = 4, message = "Password must be at least 4 characters")
  private String password;

  @NotBlank(message = "Full name is required")
  private String fullname;

  private String street;
  private String city;
  private String state;
  private String zip;
  private String phone;

  @Email(message = "Must be a valid email address")
  private String email;
  
  public User toUser(PasswordEncoder passwordEncoder) {
    return new User(
        username, passwordEncoder.encode(password), 
        fullname, street, city, state, zip, phone, email);
  }
  
}

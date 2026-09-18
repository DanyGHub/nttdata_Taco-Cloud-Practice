package tacos;
import java.util.Arrays;
import java.util.Collection;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.
                                          SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import lombok.Data;

@Data
@Document
public class User implements UserDetails {

  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  
  @org.springframework.data.mongodb.core.index.Indexed(unique = true)
  private String username;
  
  private String password;
  private String fullname;
  private String street;
  private String city;
  private String state;
  private String zip;
  private String phoneNumber;
  @org.springframework.data.mongodb.core.index.Indexed(unique = true)
  private String email;

  private List<String> roles = new ArrayList<>(Arrays.asList("ROLE_USER"));

  public User() {
  }

  public User(String username, String password, String fullname, String street,
              String city, String state, String zip, String phoneNumber,
              String email) {
    this.username = username;
    this.password = password;
    this.fullname = fullname;
    this.street = street;
    this.city = city;
    this.state = state;
    this.zip = zip;
    this.phoneNumber = phoneNumber;
    this.email = email;
    this.roles = new ArrayList<>(Arrays.asList("ROLE_USER"));
  }

  public User(String username, String password, String fullname, String street,
              String city, String state, String zip, String phoneNumber,
              String email, List<String> roles) {
    this(username, password, fullname, street, city, state, zip, phoneNumber, email);
    if (roles != null && !roles.isEmpty()) {
      this.roles = new ArrayList<>(roles);
    }
  }

  public void addRole(String role) {
    if (role != null && !role.trim().isEmpty()) {
      String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role;
      if (this.roles == null) {
        this.roles = new ArrayList<>();
      }
      if (!this.roles.contains(normalized)) {
        this.roles.add(normalized);
      }
    }
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    if (roles == null || roles.isEmpty()) {
      return Arrays.asList(new SimpleGrantedAuthority("ROLE_USER"));
    }
    return roles.stream()
        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

}

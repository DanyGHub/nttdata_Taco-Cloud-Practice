package tacos.web.api.dto;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiProblem {

  private URI type;
  private String title;
  private int status;
  private String detail;
  private String instance;
  private String code;

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  private List<Violation> violations = new ArrayList<>();

  private Date timestamp = new Date();

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String traceId;

  public ApiProblem(URI type, String title, int status, String detail, String instance, String code) {
    this.type = type != null ? type : URI.create("about:blank");
    this.title = title;
    this.status = status;
    this.detail = detail;
    this.instance = instance;
    this.code = code;
    this.timestamp = new Date();
  }

  public static ApiProblem of(HttpStatus status, String code, String title, String detail, String instance) {
    return new ApiProblem(URI.create("about:blank"), title, status.value(), detail, instance, code);
  }

  public static ApiProblem of(HttpStatus status, String code, String title, String detail, String instance, List<Violation> violations) {
    ApiProblem problem = new ApiProblem(URI.create("about:blank"), title, status.value(), detail, instance, code);
    if (violations != null) {
      problem.setViolations(violations);
    }
    return problem;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Violation {
    private String field;
    private String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object rejectedValue;

    public Violation(String field, String message) {
      this.field = field;
      this.message = message;
    }
  }

}

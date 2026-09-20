package tacos.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TacoPage<T> {

  @Builder.Default
  private List<T> content = new ArrayList<>();

  private int page;
  private int size;
  private long totalElements;
  private int totalPages;
  private boolean first;
  private boolean last;
  private boolean hasNext;
  private boolean hasPrevious;

  public static <T> TacoPage<T> of(List<T> content, int page, int size, long totalElements) {
    int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
    boolean first = page <= 0;
    boolean last = page >= totalPages - 1 || totalPages == 0;
    boolean hasNext = page < totalPages - 1;
    boolean hasPrevious = page > 0;

    return TacoPage.<T>builder()
        .content(content != null ? content : Collections.emptyList())
        .page(page)
        .size(size)
        .totalElements(totalElements)
        .totalPages(totalPages)
        .first(first)
        .last(last)
        .hasNext(hasNext)
        .hasPrevious(hasPrevious)
        .build();
  }

  public <R> TacoPage<R> map(Function<? super T, ? extends R> converter) {
    List<R> converted = content != null ? content.stream().map(converter).collect(Collectors.toList()) : Collections.emptyList();
    return TacoPage.<R>builder()
        .content(converted)
        .page(page)
        .size(size)
        .totalElements(totalElements)
        .totalPages(totalPages)
        .first(first)
        .last(last)
        .hasNext(hasNext)
        .hasPrevious(hasPrevious)
        .build();
  }
}

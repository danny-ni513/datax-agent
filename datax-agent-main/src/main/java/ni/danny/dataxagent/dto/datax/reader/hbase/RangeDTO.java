package ni.danny.dataxagent.dto.datax.reader.hbase;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@AllArgsConstructor
@ToString
public class RangeDTO {
    private String startRowkey;
    private String endRowkey;
}

package ni.danny.dataxagent.common.dto.datax.reader.oracle;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@AllArgsConstructor
@ToString
public class ConnectionDTO{
    private String[] querySql;
    private String[] jdbcUrl;
}

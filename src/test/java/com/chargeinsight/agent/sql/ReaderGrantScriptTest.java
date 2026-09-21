package com.chargeinsight.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReaderGrantScriptTest {
    @Test
    void grantsOnlySelectOnWhitelistedViews() throws Exception {
        String script = Files.readString(Path.of("infra/mysql/init/03-create-reader.sh"));

        assertThat(script).contains("GRANT SELECT ON \\`${MYSQL_DATABASE}\\`.\\`v_daily_group_operation\\`");
        assertThat(script).contains("GRANT SELECT ON \\`${MYSQL_DATABASE}\\`.\\`v_daily_fault_analysis\\`");
        assertThat(script).doesNotContain("ON \\`${MYSQL_DATABASE}\\`.*");
        assertThat(script).doesNotContain("GRANT ALL");
        assertThat(script).doesNotContain("SHOW VIEW");
    }
}

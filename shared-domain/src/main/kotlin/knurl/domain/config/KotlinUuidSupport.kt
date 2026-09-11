package knurl.domain.config

import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * JDBI only understands `java.util.UUID` out of the box (via jdbi3-postgres). The domain model
 * uses the newer, stable `kotlin.uuid.Uuid` instead (see [knurl.domain.models.InstagramPost]),
 * so this bridges binding (`Argument`) and reading (`ColumnMapper`) in both directions.
 */
class KotlinUuidArgumentFactory : AbstractArgumentFactory<Uuid>(Types.OTHER) {
    override fun build(
        value: Uuid,
        config: ConfigRegistry,
    ): Argument =
        Argument { position: Int, statement: PreparedStatement, _: StatementContext ->
            statement.setObject(position, value.toJavaUuid())
        }
}

class KotlinUuidColumnMapper : ColumnMapper<Uuid> {
    override fun map(
        rs: ResultSet,
        columnNumber: Int,
        ctx: StatementContext,
    ): Uuid = rs.getObject(columnNumber, java.util.UUID::class.java).toKotlinUuid()
}

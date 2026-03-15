-- member 테이블 생성 (MSSQL)
-- 로그인·회원가입·최근 로그인 기록용. config.txt에 DB_URL 설정 시 사용.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'member' AND schema_id = SCHEMA_ID(''))
BEGIN
    CREATE TABLE dbo.member (
        id              nvarchar(100)  NOT NULL PRIMARY KEY,
        password        nvarchar(255)  NOT NULL,
        name            nvarchar(100)  NULL,
        email           nvarchar(255)  NULL,
        last_login_time datetime2      NULL,
        last_login_ip   nvarchar(45)   NULL
    );
END
GO

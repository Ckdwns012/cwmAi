-- member 테이블에 최근 로그인 기록 컬럼 추가 (MSSQL)
-- config.txt로 DB 사용 시 로그인할 때마다 last_login_time, last_login_ip 가 갱신됩니다.

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID(N'dbo.member') AND name = 'last_login_time'
)
BEGIN
    ALTER TABLE member ADD last_login_time datetime2 NULL;
END
GO

IF NOT EXISTS (
    SELECT 1 FROM sys.columns
    WHERE object_id = OBJECT_ID(N'dbo.member') AND name = 'last_login_ip'
)
BEGIN
    ALTER TABLE member ADD last_login_ip nvarchar(45) NULL;
END
GO
